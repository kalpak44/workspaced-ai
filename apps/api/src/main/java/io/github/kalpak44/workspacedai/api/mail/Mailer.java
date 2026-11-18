package io.github.kalpak44.workspacedai.api.mail;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Sends mail; {@link MailjetMailer} sends it through Mailjet's API. */
public interface Mailer {

  /**
   * @return the id the mail service gave the message
   * @throws MailUnavailableException when nothing can be sent right now
   * @throws MailFailedException when the mail service refused the message
   */
  String send(Mail mail);

  /**
   * @param name shown beside the address; blank shows the address alone
   */
  record Address(String email, String name) {

    public static Address of(String email) {
      return new Address(email, null);
    }
  }

  /**
   * @param id what the HTML refers to it by, as {@code cid:<id>}
   */
  record InlineImage(String id, String contentType, String filename, byte[] content) {

    // A record compares an array by identity; two images with the same bytes are the same.
    @Override
    public boolean equals(Object other) {
      return other instanceof InlineImage image
          && id.equals(image.id)
          && contentType.equals(image.contentType)
          && filename.equals(image.filename)
          && Arrays.equals(content, image.content);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, contentType, filename, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
      return "InlineImage[" + id + ", " + contentType + ", " + content.length + " bytes]";
    }
  }

  /**
   * @param replyTo where replies go instead of the sender; null sends them to the sender
   * @param text the body, as plain text, for a client that shows no HTML
   * @param html the same body formatted; null sends plain text alone
   * @param images what the HTML shows from inside the mail rather than from a URL
   */
  record Mail(
      Address from,
      List<Address> to,
      Address replyTo,
      String subject,
      String text,
      String html,
      List<InlineImage> images) {}
}
