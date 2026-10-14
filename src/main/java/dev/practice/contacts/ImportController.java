package dev.practice.contacts;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ImportController {
  private final ImportService service;

  public ImportController(ImportService service) {
    this.service = service;
  }

  @GetMapping("/contacts")
  public List<ImportService.Contact> list() {
    return service.list();
  }

  @PostMapping(value = "/imports", consumes = "multipart/form-data")
  public ResponseEntity<ImportService.Report> upload(
      @RequestParam MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun) {
    var report = service.process(file, dryRun);
    return ResponseEntity.status(report.errors().isEmpty() ? 200 : 422).body(report);
  }
}
