package dev.qiqi.dataagent.video;

import dev.qiqi.dataagent.identity.IdentityService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;

@RestController
@RequestMapping("/api/runs/{runId}")
public class ReportVideoController {
    private final IdentityService identities;
    private final ReportVideoService videos;

    public ReportVideoController(IdentityService identities, ReportVideoService videos) {
        this.identities = identities; this.videos = videos;
    }
    private long owner(String username) {
        return identities.findActiveByUsername(username)
                .orElseThrow(() -> new SecurityException("Unknown or inactive demo user")).id();
    }

    @GetMapping("/video-offer")
    public Mono<ReportVideoService.Offer> offer(@RequestHeader("X-Qiqi-User") String username, @PathVariable String runId) {
        return Mono.fromCallable(() -> videos.offer(owner(username), runId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/video")
    public Mono<ReportVideoService.VideoArtifact> render(@RequestHeader("X-Qiqi-User") String username, @PathVariable String runId) {
        return Mono.fromCallable(() -> videos.render(owner(username), runId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> renderUnavailable(IllegalStateException error) {
        return Map.of("error", error.getMessage());
    }

    @GetMapping("/video/{id}")
    public Mono<ResponseEntity<FileSystemResource>> download(@RequestHeader("X-Qiqi-User") String username,
                                                               @PathVariable String runId, @PathVariable String id) {
        return Mono.fromCallable(() -> ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=qiqi-report-video.mp4")
                .body(new FileSystemResource(videos.video(owner(username), runId, id))))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
