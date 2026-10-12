package com.factoryflow.production.repository;

import com.factoryflow.production.domain.Bom;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BomRepository extends JpaRepository<Bom, Long>, BomRepositoryCustom {}
