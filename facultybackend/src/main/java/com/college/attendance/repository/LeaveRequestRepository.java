package com.college.attendance.repository;

import com.college.attendance.entity.LeaveRequest;
import com.college.attendance.enums.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<LeaveRequest> findByStatusOrderByCreatedAtDesc(LeaveStatus status);

    List<LeaveRequest> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, LeaveStatus status);

    Optional<LeaveRequest> findByApprovalToken(String token);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.user.department.id = :deptId ORDER BY lr.createdAt DESC")
    List<LeaveRequest> findByDepartmentIdOrderByCreatedAtDesc(@Param("deptId") Long departmentId);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.user.department.id = :deptId AND lr.status = :status ORDER BY lr.createdAt DESC")
    List<LeaveRequest> findByDepartmentIdAndStatus(@Param("deptId") Long departmentId, @Param("status") LeaveStatus status);

    @Query("SELECT COUNT(lr) FROM LeaveRequest lr WHERE lr.status = 'PENDING'")
    long countPendingLeaves();

    @Query("SELECT COUNT(lr) FROM LeaveRequest lr WHERE lr.user.department.id = :deptId AND lr.status = 'PENDING'")
    long countPendingLeavesByDepartment(@Param("deptId") Long departmentId);

    @Query("SELECT COUNT(lr) FROM LeaveRequest lr WHERE lr.user.id = :userId AND lr.status = 'APPROVED' AND YEAR(lr.startDate) = :year AND MONTH(lr.startDate) = :month")
    long countApprovedLeavesByMonth(@Param("userId") Long userId, @Param("year") int year, @Param("month") int month);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.user.id = :userId AND lr.status IN ('PENDING', 'APPROVED') AND lr.startDate <= :endDate AND lr.endDate >= :startDate")
    List<LeaveRequest> findOverlappingLeaves(@Param("userId") Long userId, @Param("startDate") java.time.LocalDate startDate, @Param("endDate") java.time.LocalDate endDate);

    @Modifying
    @Transactional
    @Query("DELETE FROM LeaveRequest lr WHERE lr.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(lr) FROM LeaveRequest lr WHERE lr.status = 'APPROVED' AND :date >= lr.startDate AND :date <= lr.endDate")
    long countApprovedLeavesOnDate(@Param("date") java.time.LocalDate date);

    List<LeaveRequest> findByUserIdInAndStatus(List<Long> userIds, LeaveStatus status);
}
