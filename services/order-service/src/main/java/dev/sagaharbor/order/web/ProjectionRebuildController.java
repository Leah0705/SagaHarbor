package dev.sagaharbor.order.web;

import dev.sagaharbor.order.domain.ProjectionRebuildRun;
import dev.sagaharbor.order.domain.ProjectionRebuildRunRepository;
import dev.sagaharbor.order.service.OperationsProjectionRebuildService;
import dev.sagaharbor.order.web.dto.ProjectionRebuildRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only (see SecurityConfig's /api/v1/admin/** rule, the same one dead-letter replay uses):
 * trigger a full projection rebuild and check the most recent run's status. A rebuild recomputes
 * order_operations_projection/order_stage_duration from this service's own durable tables — see
 * OperationsProjectionRebuildService's Javadoc.
 */
@RestController
@RequestMapping("/api/v1/admin/operations-projection")
public class ProjectionRebuildController {

  private final OperationsProjectionRebuildService rebuildService;
  private final ProjectionRebuildRunRepository rebuildRunRepository;

  public ProjectionRebuildController(
      OperationsProjectionRebuildService rebuildService,
      ProjectionRebuildRunRepository rebuildRunRepository) {
    this.rebuildService = rebuildService;
    this.rebuildRunRepository = rebuildRunRepository;
  }

  @Operation(summary = "Rebuild the operations projection from durable history")
  @PostMapping("/rebuild")
  public ProjectionRebuildRunResponse rebuild(@AuthenticationPrincipal Jwt jwt) {
    return toResponse(rebuildService.rebuild(jwt.getSubject()));
  }

  @Operation(summary = "The most recent rebuild run's status")
  @GetMapping("/rebuild/latest")
  public ProjectionRebuildRunResponse latest() {
    return rebuildRunRepository
        .findFirstByOrderByStartedAtDesc()
        .map(ProjectionRebuildController::toResponse)
        .orElse(null);
  }

  private static ProjectionRebuildRunResponse toResponse(ProjectionRebuildRun run) {
    return new ProjectionRebuildRunResponse(
        run.getRebuildId(),
        run.getStartedAt(),
        run.getCompletedAt(),
        run.getStatus().name(),
        run.getTriggeredBy(),
        run.getOrdersProcessed(),
        run.getFailureDetail());
  }
}
