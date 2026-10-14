package dev.practice.contacts;

import static org.springframework.http.HttpStatus.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.commons.csv.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportService {
  private final JdbcTemplate db;
  private static final Pattern EMAIL =
      Pattern.compile(
          "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

  public ImportService(JdbcTemplate db) {
    this.db = db;
  }

  public record Contact(String name, String email, String department) {}

  public record RowError(long row, String message) {}

  public record Report(boolean dryRun, int total, int valid, int imported, List<RowError> errors) {}

  @Transactional
  public Report process(MultipartFile file, boolean dryRun) {
    if (file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "CSV file is empty");
    if (file.getSize() > 1024 * 1024)
      throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "Upload exceeds the 1 MB limit");
    var contacts = new ArrayList<Contact>();
    var errors = new ArrayList<RowError>();
    var emails = new HashSet<String>();
    int total = 0;
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try (var reader =
        new PushbackReader(new InputStreamReader(file.getInputStream(), decoder), 1)) {
      int first = reader.read();
      if (first != -1 && first != 0xFEFF) reader.unread(first);
      try (var parser =
          CSVFormat.DEFAULT
              .builder()
              .setHeader()
              .setSkipHeaderRecord(true)
              .setIgnoreEmptyLines(true)
              .get()
              .parse(reader)) {
        if (!parser.getHeaderNames().equals(List.of("name", "email", "department")))
          throw new ResponseStatusException(
              BAD_REQUEST, "Header must be exactly: name,email,department");
        for (var row : parser) {
          total++;
          if (total > 1000)
            throw new ResponseStatusException(
                BAD_REQUEST, "CSV must contain at most 1000 data rows");
          long number = row.getRecordNumber() + 1;
          if (row.size() != 3) {
            errors.add(new RowError(number, "Expected 3 columns"));
            continue;
          }
          var name = row.get(0).strip();
          var email = row.get(1).strip().toLowerCase(Locale.ROOT);
          var department = row.get(2).strip();
          var problems = new ArrayList<String>();
          if (name.isBlank() || name.length() > 80)
            problems.add("name must contain 1-80 characters");
          if (email.length() > 254 || !EMAIL.matcher(email).matches())
            problems.add("invalid email");
          if (department.isBlank() || department.length() > 80)
            problems.add("department must contain 1-80 characters");
          if (!emails.add(email)) problems.add("duplicate email in CSV");
          if (db.queryForObject("SELECT COUNT(*) FROM contacts WHERE email=?", Integer.class, email)
              > 0) problems.add("email already exists");
          if (problems.isEmpty()) contacts.add(new Contact(name, email, department));
          else errors.add(new RowError(number, String.join("; ", problems)));
        }
      }
    } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
      throw new ResponseStatusException(BAD_REQUEST, "Malformed CSV or invalid UTF-8");
    }
    if (total == 0) throw new ResponseStatusException(BAD_REQUEST, "CSV has no data rows");
    if (!dryRun && errors.isEmpty())
      for (var contact : contacts)
        db.update(
            "INSERT INTO contacts(email,name,department) VALUES(?,?,?)",
            contact.email(),
            contact.name(),
            contact.department());
    return new Report(
        dryRun,
        total,
        contacts.size(),
        !dryRun && errors.isEmpty() ? contacts.size() : 0,
        List.copyOf(errors));
  }

  public List<Contact> list() {
    return db.query(
        "SELECT * FROM contacts ORDER BY email",
        (rs, n) ->
            new Contact(rs.getString("name"), rs.getString("email"), rs.getString("department")));
  }
}
