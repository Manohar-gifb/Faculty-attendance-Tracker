package com.college.attendance.repository;

import com.college.attendance.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findTop10ByOrderByCreatedAtDesc();

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByAction(String action);
}
