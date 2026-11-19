package io.bangpick.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AccountController {
    record Options(String loginPath) {}
    record Account(UUID id, String displayName, MemberRepository.Role role) {}
    record Csrf(String headerName, String token) {}

    private final MemberRepository members;
    private final GoogleLoginConfiguration.Settings settings;

    AccountController(MemberRepository members, GoogleLoginConfiguration.Settings settings) {
        this.members = members;
        this.settings = settings;
    }

    @GetMapping("/auth/options")
    ResponseEntity<Options> options(HttpServletRequest request) {
        // The browser must create its OAuth session on the host that receives the callback.
        String loginPath = settings.enabled()
                ? settings.origin().resolve(request.getContextPath() + GoogleLoginConfiguration.LOGIN_PATH).toString()
                : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Options(loginPath));
    }

    @GetMapping("/me")
    ResponseEntity<Account> me(@AuthenticationPrincipal MemberPrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var member = members.findById(principal.memberId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Account(member.id(), principal.getFullName(), member.role()));
    }

    @GetMapping("/auth/csrf")
    ResponseEntity<Csrf> csrf(CsrfToken csrf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Csrf(csrf.getHeaderName(), csrf.getToken()));
    }

    @GetMapping("/admin/access")
    ResponseEntity<Void> adminAccess() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
