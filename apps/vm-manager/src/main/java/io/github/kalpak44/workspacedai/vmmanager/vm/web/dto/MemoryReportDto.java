package io.github.kalpak44.workspacedai.vmmanager.vm.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(name = "MemoryReport", description = "A VM's memory as its kernel counts it")
public record MemoryReportDto(
    @Schema(example = "4005847040") @Positive long totalBytes,
    @Schema(
            description = "What the kernel could hand out without swapping, cache included",
            example = "2945581056")
        @PositiveOrZero
        long availableBytes) {}
