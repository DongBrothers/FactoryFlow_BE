package com.factoryflow.auth.service;

import com.factoryflow.auth.domain.Role;
import com.factoryflow.auth.dto.response.RoleResponse;
import com.factoryflow.auth.repository.RoleRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 역할 목록 (docs/specs/auth-account.md). */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;

    /** 계정 발급 화면의 역할 선택지. 표시 순서대로. */
    public List<RoleResponse> findRoles() {
        return findAllRoles().stream().map(RoleResponse::from).toList();
    }

    private List<Role> findAllRoles() {
        return roleRepository.findAllByOrderBySortOrderAsc();
    }
}
