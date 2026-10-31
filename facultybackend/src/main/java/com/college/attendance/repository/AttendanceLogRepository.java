package com.college.attendance.repository;

import com.college.attendance.entity.AttendanceLog;
import com.college.attendance.enums.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceLogRepository extends JpaRepository<AttendanceLog, Long> {

    Optional<AttendanceLog> findByUserIdAndAttendanceDate(Long userId, LocalDate date);

    long countByAttendanceDate(LocalDate date);

    List<AttendanceLog> findByAttendanceDate(LocalDate date);

    /** Today's logs for a bounded set of user ids (paginated admin view). */
    List<AttendanceLog> findByUserIdInAndAttendanceDate(Collection<Long> userIds, LocalDate date);

    List<AttendanceLog> findByUserIdAndAttendanceDateBetween(Long userId, LocalDate startDate, LocalDate endDate);

    List<AttendanceLog> findByUserIdOrderByAttendanceDateDesc(Long userId);

    @Query("SELECT al FROM AttendanceLog al WHERE al.attendanceDate = :date AND al.user.department.id = :deptId")
    List<AttendanceLog> findByDateAndDepartment(@Param("date") LocalDate date, @Param("deptId") Long departmentId);

    @Query("SELECT al FROM AttendanceLog al WHERE al.attendanceDate = :date AND al.status = :status")
    List<AttendanceLog> findByDateAndStatus(@Param("date") LocalDate date, @Param("status") AttendanceStatus status);

    @Query("SELECT COUNT(al) FROM AttendanceLog al WHERE al.attendanceDate = :date AND al.status = :status")
    long countByDateAndStatus(@Param("date") LocalDate date, @Param("status") AttendanceStatus status);

    @Query("SELECT COUNT(al) FROM AttendanceLog al WHERE al.attendanceDate = :date AND al.user.department.id = :deptId AND al.status = :status")
    long countByDateAndDepartmentAndStatus(@Param("date") LocalDate date, @Param("deptId") Long departmentId, @Param("status") AttendanceStatus status);

    @Query("SELECT al FROM AttendanceLog al WHERE al.attendanceDate = :date AND al.user.department.id = :deptId ORDER BY al.user.lastName")
    List<AttendanceLog> findDepartmentAttendanceByDate(@Param("date") LocalDate date, @Param("deptId") Long departmentId);

    @Query("SELECT al FROM AttendanceLog al WHERE al.user.id = :userId AND YEAR(al.attendanceDate) = :year AND MONTH(al.attendanceDate) = :month ORDER BY al.attendanceDate")
    List<AttendanceLog> findMonthlyAttendance(@Param("userId") Long userId, @Param("year") int year, @Param("month") int month);

    @Query("SELECT COUNT(al) FROM AttendanceLog al WHERE al.user.department.id = :deptId AND al.attendanceDate BETWEEN :start AND :end AND al.status = 'PRESENT'")
    long countPresentInRangeByDepartment(@Param("deptId") Long departmentId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Modifying
    @Transactional
    @Query("DELETE FROM AttendanceLog al WHERE al.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
