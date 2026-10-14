package dev.practice.contacts;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ImportService service;

  private static final String CSV =
      "name,email,department\nKim,kim@example.com,Backend\n\"Lee, Min\",lee@example.com,Platform\n";

  @BeforeEach
  void reset() {
    db.update("DELETE FROM contacts");
  }

  private org.springframework.mock.web.MockMultipartFile file(String content) {
    return new org.springframework.mock.web.MockMultipartFile(
        "file",
        "contacts.csv",
        "text/csv",
        content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void dryRunIsDefaultAndNeverWrites() throws Exception {
    mvc.perform(multipart("/api/imports").file(file(CSV)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.valid").value(2))
        .andExpect(jsonPath("$.imported").value(0));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class)).isZero();
  }

  @Test
  void importsQuotedFieldsAndListsContacts() throws Exception {
    mvc.perform(multipart("/api/imports").file(file(CSV)).param("dryRun", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imported").value(2));
    mvc.perform(get("/api/contacts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[1].name").value("Lee, Min"));
  }

  @Test
  void invalidRowMakesEntireImportFail() throws Exception {
    mvc.perform(
            multipart("/api/imports")
                .file(file(CSV + "Park,bad-email,Backend\n"))
                .param("dryRun", "false"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.valid").value(2))
        .andExpect(jsonPath("$.imported").value(0))
        .andExpect(jsonPath("$.errors[0].row").value(4));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class)).isZero();
  }

  @Test
  void rejectsCaseInsensitiveDuplicatesAndExistingEmails() throws Exception {
    mvc.perform(
            multipart("/api/imports")
                .file(file(CSV + "Other,KIM@example.com,Backend\n"))
                .param("dryRun", "false"))
        .andExpect(status().isUnprocessableEntity());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class)).isZero();
    mvc.perform(multipart("/api/imports").file(file(CSV)).param("dryRun", "false"))
        .andExpect(status().isOk());
    mvc.perform(multipart("/api/imports").file(file(CSV)).param("dryRun", "false"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors.length()").value(2));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class)).isEqualTo(2);
  }

  @Test
  void rejectsWrongHeaderMalformedCsvAndInvalidUtf8() throws Exception {
    mvc.perform(multipart("/api/imports").file(file("email,name,department\na,b,c")))
        .andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/imports").file(file("name,email,department\n\"unclosed")))
        .andExpect(status().isBadRequest());
    var bytes =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "bad.csv", "text/csv", new byte[] {(byte) 0xff, (byte) 0xfe});
    mvc.perform(multipart("/api/imports").file(bytes)).andExpect(status().isBadRequest());
  }

  @Test
  void acceptsBomButRejectsEmptyAndHeaderOnly() throws Exception {
    mvc.perform(multipart("/api/imports").file(file("\uFEFF" + CSV))).andExpect(status().isOk());
    mvc.perform(multipart("/api/imports").file(file(""))).andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/imports").file(file("name,email,department\n")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsMoreThan1000Rows() throws Exception {
    var csv = new StringBuilder("name,email,department\n");
    for (int i = 0; i < 1001; i++) csv.append("Kim,u").append(i).append("@example.com,Backend\n");
    mvc.perform(multipart("/api/imports").file(file(csv.toString())).param("dryRun", "false"))
        .andExpect(status().isBadRequest());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class)).isZero();
  }

  @Test
  void concurrentImportRollsBackEveryRowOnConflict() throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 2; i++) {
        final int index = i;
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  var input =
                      file(
                          "name,email,department\nUnique,u"
                              + index
                              + "@example.com,Backend\nCommon,common@example.com,Backend\n");
                  try {
                    return service.process(input, false).errors().isEmpty() ? 200 : 422;
                  } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    return 409;
                  }
                }));
      }
      gate.countDown();
      var results = new java.util.ArrayList<Integer>();
      for (var future : futures) results.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(results).filteredOn(code -> code == 200).hasSize(1);
      assertThat(results).filteredOn(code -> code == 409 || code == 422).hasSize(1);
      assertThat(service.list()).hasSize(2);
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM contacts WHERE email LIKE 'u%@example.com'", Integer.class))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
