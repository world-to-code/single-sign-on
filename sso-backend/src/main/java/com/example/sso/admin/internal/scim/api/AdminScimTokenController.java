package com.example.sso.admin.internal.scim.api;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.scim.IssueScimTokenRequest;
import com.example.sso.scim.ScimTokenIssued;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for issuing SCIM provisioning bearer tokens. */
@RestController
@RequestMapping("/api/admin/scim/tokens")
@RequiredArgsConstructor
public class AdminScimTokenController {

    private final AdminService adminService;

    @PostMapping
    @RequirePermission(Permissions.SCIM_MANAGE)
    public ScimTokenIssued issueScimToken(@Valid @RequestBody IssueScimTokenRequest request) {
        return adminService.issueScimToken(request);
    }
}
