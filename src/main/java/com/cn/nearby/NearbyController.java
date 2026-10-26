package com.cn.nearby;

import com.cn.dto.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/nearby")
public class NearbyController {
    private final AmapNearbyService service;
    private final NearbyRateLimiter limiter;

    public NearbyController(AmapNearbyService service, NearbyRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @GetMapping("/pois")
    public Result search(@RequestParam Double longitude, @RequestParam Double latitude,
                         @RequestParam(defaultValue = "food") String category,
                         @RequestParam(defaultValue = "1000") int radius,
                         @RequestParam(defaultValue = "") String keyword,
                         @RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        keyword = AmapNearbyService.validate(longitude, latitude, category, radius, keyword, page);
        limiter.check(request);
        return Result.ok(service.search(longitude, latitude, category, radius, keyword, page));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result> failure(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Result.fail(exception.getReason()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Result> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Result.fail("请提供有效的查询参数"));
    }
}
