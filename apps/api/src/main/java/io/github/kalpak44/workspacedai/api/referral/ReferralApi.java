package io.github.kalpak44.workspacedai.api.referral;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The signed-in user's referrals: the route and its OpenAPI documentation.
 *
 * @see ReferralController
 */
@Tag(
    name = "Referrals",
    description =
        "Invite friends with a link; both sides earn balance credit on the friend's paid top-ups")
@RequestMapping("/account/referrals")
public interface ReferralApi {

  String JSON = "application/json";

  @Operation(
      operationId = "getReferrals",
      summary = "Get the invitation link and the friends it brought",
      description =
          "Makes the user's code the first time. Friends are shown by when they signed up and"
              + " paid, never by name.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Referrals.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<Referrals> get();
}
