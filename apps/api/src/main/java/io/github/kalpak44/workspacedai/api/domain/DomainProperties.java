package io.github.kalpak44.workspacedai.api.domain;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param privateKey the PKCS#8 PEM key Domain Connect apply requests are signed with; blank leaves
 *     every domain to be set up by hand
 * @param keyName where a DNS provider finds the public half, under {@code providerId}
 * @param providerId ours in Domain Connect, as the templates name it; also a domain no user may
 *     attach
 * @param appUrl the web app, which a DNS provider sends the user back to
 * @param resolver the DNS server names are checked against, as a JNDI DNS URL; a public one, so a
 *     record the user adds is seen as the internet sees it
 * @param timeout how long one DNS query or provider call may take
 * @param maxPerWorkspace how many names one workspace may have attached
 * @param liveProviders the DNS providers, by their Domain Connect provider id, whose onboarding of
 *     our templates is live; a provider answers for a template while it is still limited to our
 *     test account, so any other is left to set up by hand
 * @param testZones zones offered Domain Connect at any provider, to test a template before it goes
 *     live
 */
@ConfigurationProperties("workspaced.domains")
public record DomainProperties(
    String privateKey,
    @DefaultValue("_dck1") String keyName,
    @DefaultValue("workspaced.online") String providerId,
    @DefaultValue("https://ai.workspaced.online") String appUrl,
    @DefaultValue("dns://1.1.1.1") String resolver,
    @DefaultValue("5s") Duration timeout,
    @DefaultValue("20") int maxPerWorkspace,
    @DefaultValue List<String> liveProviders,
    @DefaultValue List<String> testZones) {}
