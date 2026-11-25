package io.github.kalpak44.workspacedai.api.referral;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@Schema(description = "The signed-in user's invitation link, its terms, and the friends it brought")
public record Referrals(
    @Schema(description = "Whether the program runs at all") boolean enabled,
    @Schema(description = "False for an admin, who takes part on neither side") boolean eligible,
    @Schema(description = "Null while not eligible", example = "k7m2xq9p") String code,
    @Schema(
            description = "Null while not eligible",
            example = "https://workspaced.online/?ref=k7m2xq9p")
        URI link,
    @Schema(description = "What an invited friend gets") Terms referee,
    @Schema(description = "What the user gets for each friend") Terms referrer,
    @Schema(description = "Friends who signed up with the link", example = "4") int invited,
    @Schema(description = "Of them, those who have paid", example = "2") int paying,
    @Schema(description = "Credited so far for every friend together, in cents", example = "1250")
        long earnedCents,
    @Schema(description = "Newest first") List<Friend> friends) {

  @Schema(description = "One side of the program")
  public record Terms(
      @Schema(description = "Of each paid top-up before VAT", example = "10") BigDecimal percent,
      @Schema(description = "The most for one friend, in cents", example = "5000") long capCents,
      @Schema(description = "How long after the friend's sign-up it runs", example = "365")
          long days) {}

  /** Where an invited friend is: no name, since names stay in Auth0. */
  public enum Status {
    /** Signed up, has not paid yet. */
    SIGNED_UP,
    /** Has paid, and their top-ups still earn. */
    EARNING,
    /** The cap is reached or the period is over. */
    FINISHED
  }

  @Schema(description = "A friend who signed up with the link")
  public record Friend(
      Instant signedUpAt,
      @Schema(description = "Null while they have not paid") Instant firstPaidAt,
      Status status,
      @Schema(description = "Credited to the user for this friend, in cents", example = "750")
          long earnedCents,
      @Schema(description = "The friend's top-ups earn until then") Instant earningUntil) {}
}
