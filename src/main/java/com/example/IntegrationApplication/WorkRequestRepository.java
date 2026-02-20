package com.example.IntegrationApplication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkRequestRepository extends JpaRepository<WorkRequest, Long> {
    Optional<WorkRequest> findByMaintainxId(Long maintainxId);
}
