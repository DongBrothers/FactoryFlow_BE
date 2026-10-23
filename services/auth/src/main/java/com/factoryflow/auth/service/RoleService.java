package com.factoryflow.auth.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.factoryflow.auth.domain.Permission;
import com.factoryflow.auth.domain.Role;
import com.factoryflow.auth.domain.RolePermission;
import com.factoryflow.auth.dto.request.ReplaceRolePermissionsRequest;
import com.factoryflow.auth.dto.response.PermissionResponse;
import com.factoryflow.auth.dto.response.RoleResponse;
import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.auth.repository.PermissionRepository;
import com.factoryflow.auth.repository.RolePermissionRepository;
import com.factoryflow.auth.repository.RoleRepository;
import com.factoryflow.auth.service.redis.RolePermissionBroadcaster;
import com.factoryflow.common.web.exception.CustomException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 역할 목록과 역할 권한 관리, gateway 용 표 동기화 (docs/specs/auth-account.md, role-permission-admin.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    /** 이 권한을 가진 역할이 하나도 없으면 아무도 권한을 되돌릴 수 없다. */
    private static final String ACCOUNT_ADMIN = "ACCOUNT_ADMIN";

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RolePermissionWriter rolePermissionWriter;
    private final RolePermissionBroadcaster broadcaster;

    /** 역할 목록 (발급 화면의 선택지, 권한 편집 화면). 표시 순서대로, 역할마다 권한 코드. */
    public List<RoleResponse> findRoles() {
        Map<String, List<String>> table = loadTable();
        return findAllRoles().stream()
                .map(role -> RoleResponse.of(role, table.get(role.getCode())))
                .toList();
    }

    /** 권한 편집 화면의 선택지. 코드 순. */
    public List<PermissionResponse> findPermissions() {
        return findAllPermissions().stream().map(PermissionResponse::from).toList();
    }

    /**
     * 역할의 권한을 요청한 목록으로 통째로 바꾸고, 트랜잭션이 끝난 뒤 표를 Redis 로 보낸다. 저장 뒤 Redis 쓰기가 실패하면 503 이고, 같은 내용으로 다시
     * 저장하면 다시 쓴다.
     */
    public RoleResponse replacePermissions(String code, ReplaceRolePermissionsRequest request) {
        Role role = findRoleByCode(code);
        List<Permission> permissions = findPermissionsByCodes(new TreeSet<>(request.permissions()));
        List<String> codes = permissions.stream().map(Permission::getCode).sorted().toList();
        Map<String, List<String>> changed = loadTable();
        changed.put(role.getCode(), codes);
        requireAccountAdmin(changed);
        rolePermissionWriter.replace(
                role.getId(), permissions.stream().map(Permission::getId).toList());
        broadcastAfterChange();
        return RoleResponse.of(role, codes);
    }

    /** 역할이 가진 권한 코드 (내 정보). 코드 순. */
    public List<String> findPermissionCodes(String roleCode) {
        return loadTable().getOrDefault(roleCode, List.of());
    }

    /** 표 전체를 Redis 로 보낸다 (auth 가 뜰 때, Redis 에 다시 연결됐을 때). 실패해도 로그만 남긴다. */
    public void broadcastQuietly() {
        try {
            broadcaster.broadcast(loadTable());
        } catch (RuntimeException e) {
            log.error("[auth] 역할 권한 표를 Redis 에 쓰지 못함", e);
        }
    }

    private void broadcastAfterChange() {
        try {
            broadcaster.broadcast(loadTable());
        } catch (RuntimeException e) {
            log.error("[auth] 역할 권한을 저장했지만 Redis 에 쓰지 못함", e);
            throw new CustomException(AuthErrorCode.ROLE_PERMISSION_SYNC_UNAVAILABLE);
        }
    }

    /** 역할 코드 → 권한 코드(코드 순). 역할은 sort_order 순이고, 권한이 없는 역할은 빈 목록이다. */
    private Map<String, List<String>> loadTable() {
        Map<Long, String> permissionCodes =
                findAllPermissions().stream()
                        .collect(toMap(Permission::getId, Permission::getCode));
        Map<Long, List<String>> byRole =
                findAllRolePermissions().stream()
                        .collect(
                                groupingBy(
                                        RolePermission::getRoleId,
                                        mapping(
                                                rp -> permissionCodes.get(rp.getPermissionId()),
                                                toList())));
        Map<String, List<String>> table = new LinkedHashMap<>();
        for (Role role : findAllRoles()) {
            List<String> codes = byRole.getOrDefault(role.getId(), List.of());
            table.put(role.getCode(), codes.stream().sorted().toList());
        }
        return table;
    }

    private static void requireAccountAdmin(Map<String, List<String>> table) {
        if (table.values().stream().noneMatch(codes -> codes.contains(ACCOUNT_ADMIN))) {
            throw new CustomException(AuthErrorCode.ACCOUNT_ADMIN_REQUIRED);
        }
    }

    /** 요청한 코드의 권한. 하나라도 없는 코드면 400. */
    private List<Permission> findPermissionsByCodes(Set<String> codes) {
        List<Permission> found =
                findAllPermissions().stream().filter(p -> codes.contains(p.getCode())).toList();
        if (found.size() != codes.size()) {
            throw new CustomException(AuthErrorCode.INVALID_PERMISSION);
        }
        return found;
    }

    private Role findRoleByCode(String code) {
        return roleRepository
                .findByCode(code)
                .orElseThrow(() -> new CustomException(AuthErrorCode.ROLE_NOT_FOUND));
    }

    private List<Role> findAllRoles() {
        return roleRepository.findAllByOrderBySortOrderAsc();
    }

    private List<Permission> findAllPermissions() {
        return permissionRepository.findAllByOrderByCodeAsc();
    }

    private List<RolePermission> findAllRolePermissions() {
        return rolePermissionRepository.findAll();
    }
}
