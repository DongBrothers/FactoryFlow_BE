package com.factoryflow.order.service;

import com.factoryflow.order.domain.Customer;
import com.factoryflow.order.dto.request.CreateCustomerRequest;
import com.factoryflow.order.dto.response.CustomerResponse;
import com.factoryflow.order.repository.CustomerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객 등록과 목록 (docs/specs/customer.md). */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    @Transactional
    public CustomerResponse register(CreateCustomerRequest request) {
        Customer customer =
                customerRepository.save(Customer.register(request.name(), request.contact()));
        return CustomerResponse.from(customer);
    }

    /** 기준 정보라 페이지 없이 한 번에, 등록 순. */
    @Transactional(readOnly = true)
    public List<CustomerResponse> findCustomers() {
        return findAllCustomers().stream().map(CustomerResponse::from).toList();
    }

    private List<Customer> findAllCustomers() {
        return customerRepository.findAllByOrderByIdAsc();
    }
}
