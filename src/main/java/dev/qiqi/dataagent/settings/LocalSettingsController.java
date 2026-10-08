package dev.qiqi.dataagent.settings;

import org.springframework.context.annotation.Profile;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.net.URI;
import java.util.Map;
import java.util.Set;

@RestController
@Profile("local")
@RequestMapping("/api/settings")
public class LocalSettingsController {
    private final LocalSettingsService service;
    public LocalSettingsController(LocalSettingsService service) { this.service=service; }
    private void check(ServerHttpRequest request) {
        var remote=request.getRemoteAddress();
        String host=request.getURI().getHost();
        if(remote==null || remote.getAddress()==null || !remote.getAddress().isLoopbackAddress()
                || host==null || !Set.of("localhost","127.0.0.1","[::1]","::1").contains(host)
                || !"1".equals(request.getHeaders().getFirst("X-Qiqi-Settings")))
            throw new SecurityException("设置仅允许从本机应用访问");
        String origin=request.getHeaders().getOrigin();
        if(origin!=null) {
            try {
                URI uri=URI.create(origin);
                if(!request.getURI().getScheme().equals(uri.getScheme()) || !request.getURI().getAuthority().equals(uri.getAuthority()))
                    throw new SecurityException("不允许跨站修改设置");
            } catch(IllegalArgumentException e) {throw new SecurityException("无效来源");}
        }
    }
    @GetMapping public Mono<Map<String,Object>> get(ServerHttpRequest request) {
        check(request); return Mono.fromCallable(service::view).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping(value="/model", consumes="application/json")
    public Mono<Map<String,Object>> model(ServerHttpRequest request,@RequestBody LocalSettingsService.ModelInput input) {
        check(request); return Mono.fromCallable(()-> {service.saveModel(input);return service.view();}).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping(value="/database", consumes="application/json")
    public Mono<Map<String,Object>> database(ServerHttpRequest request,@RequestBody LocalSettingsService.DatabaseInput input) {
        check(request);return Mono.fromCallable(()-> {service.saveDatabase(input);return service.view();}).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping(value="/creative", consumes="application/json")
    public Mono<Map<String,Object>> creative(ServerHttpRequest request,@RequestBody LocalSettingsService.CreativeInput input) {
        check(request); return Mono.fromCallable(()-> {service.saveCreative(input);return service.view();}).subscribeOn(Schedulers.boundedElastic());
    }
}
