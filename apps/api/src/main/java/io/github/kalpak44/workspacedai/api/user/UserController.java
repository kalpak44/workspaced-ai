package io.github.kalpak44.workspacedai.api.user;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link UserApi}. */
@RestController
public class UserController implements UserApi {

  private final UserService service;

  public UserController(UserService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<PagedModel<User>> list(Pageable pageable) {
    return ResponseEntity.ok(new PagedModel<>(service.list(pageable)));
  }

  @Override
  public ResponseEntity<User> create(CreateUserRequest request) {
    var user =
        service.create(
            request.trimmedUsername(),
            request.password(),
            request.trimmedFirstName(),
            request.trimmedLastName(),
            request.roleOrUser());
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(user.id())
            .toUri();
    return ResponseEntity.created(location).body(user);
  }

  @Override
  public ResponseEntity<User> changeRole(UUID id, RoleRequest request) {
    return ResponseEntity.ok(
        service
            .changeRole(id, request.role(), CurrentUser.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no user " + id)));
  }

  @Override
  public ResponseEntity<Void> delete(UUID id) {
    if (!service.delete(id, CurrentUser.id())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no user " + id);
    }
    return ResponseEntity.noContent().build();
  }
}
