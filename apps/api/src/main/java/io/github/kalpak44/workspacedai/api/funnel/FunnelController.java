package io.github.kalpak44.workspacedai.api.funnel;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Routes and documentation are declared on {@link FunnelApi}. */
@RestController
public class FunnelController implements FunnelApi {

  private final FunnelService service;

  public FunnelController(FunnelService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Funnel> funnel(LocalDate from, LocalDate to) {
    var last = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
    var first = from == null ? last.minusDays(29) : from;
    if (first.isAfter(last) || first.plusYears(1).isBefore(last)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "from must be on or before to, and at most a year earlier");
    }
    return ResponseEntity.ok(service.funnel(first, last));
  }
}
