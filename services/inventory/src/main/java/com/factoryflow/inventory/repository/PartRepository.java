package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.Part;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartRepository extends JpaRepository<Part, Long> {

    List<Part> findAllByIdInAndIsDeletedFalse(Collection<Long> ids);
}
