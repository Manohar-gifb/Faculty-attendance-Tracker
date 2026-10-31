package com.college.attendance.repository;

import com.college.attendance.entity.User;
import com.college.attendance.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * The JWT filter loads the user outside any request-bound session, so the
     * lazy department association must be fetched here — controllers such as
     * ProfileController read department.name on this instance and previously
     * failed with LazyInitializationException (HTTP 400).
     */
    @EntityGraph(attributePaths = {"department", "leaveApprover", "secondaryApprover"})
    Optional<User> findByEmail(String email);

    Optional<User> findByEmployeeId(String employeeId);

    Optional<User> findByPasswordSetupToken(String passwordSetupToken);

    boolean existsByEmail(String email);

    boolean existsByEmployeeId(String employeeId);

    List<User> findByRole(Role role);

    long countByRole(Role role);

    long countByRoleAndIsActiveTrue(Role role);

    long countByDepartmentId(Long departmentId);

    List<User> findByDepartmentId(Long departmentId);

    /**
     * All profile-photo URLs currently referenced by any account row — the seed
     * for the orphan-detection referenced set in PhotoCleanupService (FILE-04).
     * Rows are NOT filtered by isActive: a deactivated account still owns its
     * registered face photo and its file must never be pruned.
     */
    @Query("select u.profilePictureUrl from User u where u.profilePictureUrl is not null and u.profilePictureUrl <> ''")
    List<String> findAllProfilePictureUrls();

    List<User> findByDepartmentIdAndRole(Long departmentId, Role role);

    List<User> findTop5ByOrderByCreatedAtDesc();

    /** Accounts whose activation/setup password link is still outstanding. */
    @Query("SELECT COUNT(u) FROM User u WHERE u.passwordSetupToken IS NOT NULL")
    long countPendingActivation();

    /** Activated, enabled accounts (no pending setup link). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.passwordSetupToken IS NULL AND u.isActive = true")
    long countActiveAccounts();

    /** Disabled accounts (no pending setup link). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.passwordSetupToken IS NULL AND u.isActive = false")
    long countInactiveAccounts();

    /** role -> count, single grouped query for dashboard role tabs. */
    @Query("SELECT u.role, COUNT(u) FROM User u GROUP BY u.role")
    List<Object[]> countGroupByRole();

    @Query("SELECT u.department.id, COUNT(u) FROM User u WHERE u.department IS NOT NULL GROUP BY u.department.id")
    List<Object[]> countUsersGroupByDepartment();

    @Query("SELECT u.department.id, COUNT(u) FROM User u WHERE u.department IS NOT NULL AND u.role = :role GROUP BY u.department.id")
    List<Object[]> countUsersGroupByDepartmentAndRole(@Param("role") Role role);

    @Query("SELECT u.department.id, COUNT(u) FROM User u WHERE u.department IS NOT NULL AND u.isActive = true GROUP BY u.department.id")
    List<Object[]> countActiveUsersGroupByDepartment();

    @Query("SELECT u FROM User u WHERE u.role = 'FACULTY' AND u.isActive = true")
    List<User> findAllActiveFaculty();

    /** Page-based variant used by the paginated /attendance/admin/all view. */
    @Query("SELECT u FROM User u WHERE u.role = 'FACULTY' AND u.isActive = true")
    Page<User> findAllActiveFaculty(Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.department.id = :deptId AND u.role = 'FACULTY' AND u.isActive = true")
    List<User> findActiveFacultyByDepartment(@Param("deptId") Long departmentId);

    @Query("SELECT u FROM User u WHERE u.role IN ('HOD') AND u.isActive = true")
    List<User> findAllActiveHODs();

    @Query("SELECT COUNT(u) FROM User u WHERE u.role = 'FACULTY' AND u.isActive = true AND u.department.id = :deptId")
    long countActiveFacultyByDepartment(@Param("deptId") Long departmentId);

    List<User> findByLeaveApprover(User leaveApprover);

    List<User> findBySecondaryApprover(User secondaryApprover);
}
