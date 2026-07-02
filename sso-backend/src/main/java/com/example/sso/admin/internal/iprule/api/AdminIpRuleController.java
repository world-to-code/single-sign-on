package com.example.sso.admin.internal.iprule.api;

import com.example.sso.session.IpRuleRequest;
import com.example.sso.session.IpRuleService;
import com.example.sso.session.IpRuleView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for network (IP) access rules enforced ahead of authentication. */
@RestController
@RequestMapping("/api/admin/ip-rules")
@RequiredArgsConstructor
public class AdminIpRuleController {

    private final IpRuleService ipRules;

    @GetMapping
    @RequirePermission(Permissions.IP_RULE_READ)
    public List<IpRuleView> ipRules() {
        return ipRules.list();
    }

    @PostMapping
    @RequirePermission(Permissions.IP_RULE_CREATE)
    public ResponseEntity<IpRuleView> createIpRule(@Valid @RequestBody IpRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ipRules.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.IP_RULE_UPDATE)
    public IpRuleView updateIpRule(@PathVariable UUID id, @Valid @RequestBody IpRuleRequest request) {
        return ipRules.update(id, request);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.IP_RULE_DELETE)
    public ResponseEntity<Void> deleteIpRule(@PathVariable UUID id) {
        ipRules.delete(id);
        return ResponseEntity.noContent().build();
    }
}
