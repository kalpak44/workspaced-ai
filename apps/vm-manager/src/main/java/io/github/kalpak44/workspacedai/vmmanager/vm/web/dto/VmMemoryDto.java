package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "VmMemory", description = "A VM's memory as its last heartbeat reported it")
public record VmMemoryDto(
    @Schema(example = "4005847040") long totalBytes,
    @Schema(
            description = "What the kernel could hand out without swapping, cache included",
            example = "2945581056")
        long availableBytes,
    @Schema(description = "When the heartbeat carrying it arrived") Instant reportedAt) {}
