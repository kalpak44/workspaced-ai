package io.github.kalpak44.workspacedai.api.mail;

import java.util.List;

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
   * @param replyTo where replies go instead of the sender; null sends them to the sender
   * @param text the body, as plain text
   */
  record Mail(Address from, List<Address> to, Address replyTo, String subject, String text) {}
}
