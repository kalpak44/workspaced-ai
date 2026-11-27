package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** An account that signs in and owns workspaces. */
@Entity
@Table(name = "app_user")
public class UserEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // The Auth0 user this account is. Their email, name and picture stay in Auth0.
  @Column(name = "idp_subject", nullable = false, unique = true, updatable = false)
  private String idpSubject;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role = Role.USER;

  // Set by the column default.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  // A session token issued before this is refused; signing out moves it to now.
  @Column(name = "sessions_valid_after", insertable = false, nullable = false)
  private Instant sessionsValidAfter;

  // When the account's one free demo workspace started; null while it can still have one.
  @Column(name = "demo_started_at", insertable = false, updatable = false)
  private Instant demoStartedAt;

  // False when another account already had the free trial with this one's email address.
  @Column(name = "free_trial", insertable = false, updatable = false)
  private boolean freeTrial = true;

  // Where the guided tour is; tourStep is null while no tour is under way.
  @Column(name = "tour_step")
  private Integer tourStep;

  @Column(name = "tour_workspace_id", length = 100)
  private String tourWorkspaceId;

  @Column(name = "tour_session_id", length = 100)
  private String tourSessionId;

  @Column(name = "tour_draft_id", length = 100)
  private String tourDraftId;

  @Column(name = "tour_name", length = 100)
  private String tourName;

  @Column(name = "tour_paused", nullable = false)
  private boolean tourPaused;

  // Set by the column default to true: reminders are mailed until the user turns them off.
  @Column(name = "signup_source", length = 100, updatable = false)
  private String signupSource;

  @Column(name = "signup_campaign", length = 100, updatable = false)
  private String signupCampaign;

  @Column(name = "balance_reminder_mail", insertable = false, nullable = false)
  private boolean balanceReminderMail = true;

  @Column(name = "tips_mail", insertable = false, nullable = false)
  private boolean tipsMail = true;

  @Column(name = "ai_credit_warning_usd", nullable = false, precision = 8, scale = 2)
  private java.math.BigDecimal aiCreditWarningUsd = java.math.BigDecimal.ONE;

  public java.math.BigDecimal getAiCreditWarningUsd() {
    return aiCreditWarningUsd;
  }

  public void setAiCreditWarningUsd(java.math.BigDecimal threshold) {
    aiCreditWarningUsd = threshold;
  }

  protected UserEntity() {
    // For JPA.
  }

  /** An account made by a sign-up that a link with that attribution started, if one did. */
  public UserEntity(String idpSubject, Role role, Attribution attribution) {
    this.idpSubject = idpSubject;
    this.role = role;
    this.signupSource = attribution.source();
    this.signupCampaign = attribution.campaign();
  }

  public UUID getId() {
    return id;
  }

  public String getIdpSubject() {
    return idpSubject;
  }

  public Role getRole() {
    return role;
  }

  public boolean isDemoAvailable() {
    return freeTrial && demoStartedAt == null;
  }

  /** Whether the account gets the free demo and the trial AI key at all. */
  public boolean hasFreeTrial() {
    return freeTrial;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Null until the account's one demo was taken. */
  public Instant getDemoStartedAt() {
    return demoStartedAt;
  }

  /** utm_source of the link that started the sign-up; null for none. */
  public String getSignupSource() {
    return signupSource;
  }

  /** utm_campaign of that link; null for none. */
  public String getSignupCampaign() {
    return signupCampaign;
  }

  /**
   * Revokes every session token issued before now. Tokens carry whole seconds, so the time is cut
   * to the second: a sign-in later in this same second stays valid.
   */
  public void signOutEverywhere(Instant now) {
    this.sessionsValidAfter = now.truncatedTo(ChronoUnit.SECONDS);
  }

  public MailPreferences getMailPreferences() {
    return new MailPreferences(balanceReminderMail, tipsMail);
  }

  public void setMailPreferences(MailPreferences preferences) {
    if (preferences.balanceReminders() != null) {
      balanceReminderMail = preferences.balanceReminders();
    }
    if (preferences.tips() != null) {
      tipsMail = preferences.tips();
    }
  }

  /** Null while no tour is under way. */
  public TourProgress getTourProgress() {
    return tourStep == null
        ? null
        : new TourProgress(
            tourStep, tourWorkspaceId, tourSessionId, tourDraftId, tourName, tourPaused);
  }

  /** Null ends the tour, finished or no longer possible. */
  public void setTourProgress(TourProgress progress) {
    tourStep = progress == null ? null : progress.step();
    tourWorkspaceId = progress == null ? null : progress.workspaceId();
    tourSessionId = progress == null ? null : progress.sessionId();
    tourDraftId = progress == null ? null : progress.draftId();
    tourName = progress == null ? null : progress.name();
    tourPaused = progress != null && progress.paused();
  }

  /** Takes the role Auth0 assigns the user at this sign-in. */
  public void signedIn(Role role) {
    this.role = role;
  }
}
