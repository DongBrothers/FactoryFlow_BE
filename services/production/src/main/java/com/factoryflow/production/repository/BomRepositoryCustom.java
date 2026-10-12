package com.factoryflow.production.repository;

import java.util.Collection;
import java.util.List;

public interface BomRepositoryCustom {

    List<Long> findProductIdsHavingBom(Collection<Long> productIds);
}
