package io.github.kalpak44.workspacedai.vmmanager;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled for the whole app, rather than on whichever component happens to use it
 * first. One instance runs, so no job needs a lock.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {}
