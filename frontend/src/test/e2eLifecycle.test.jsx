import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import HomeTab from '../components/faculty/HomeTab';
import LeaveBalance from '../pages/LeaveBalance';
import LeaveApplicationForm from '../components/faculty/LeaveApplicationForm';
import { attendanceAPI, leaveAPI } from '../services/api';
import { useAuth } from '../context/AuthContext';

vi.mock('../services/api', () => ({
  attendanceAPI: {
    getTodayAttendance: vi.fn(),
    punchIn: vi.fn(),
    punchOut: vi.fn(),
    getHistory: vi.fn(),
  },
  teamAPI: {
    getDepartmentFaculty: vi.fn(),
  },
  imageSrc: vi.fn((url) => url),
  leaveAPI: {
    getMyLeaves: vi.fn(),
  },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

describe('End-to-End User Lifecycle Workflows (Frontend)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuth.mockReturnValue({
      user: { id: 101, fullName: 'Prof. Katherine Johnson', role: 'FACULTY' },
    });
  });

  describe('Attendance Lifecycle: Unchecked -> Verify Barrier -> Punch In -> Active -> Punch Out -> Complete', () => {
    const renderHomeTab = (props = {}) =>
      render(
        <MemoryRouter>
          <HomeTab {...props} />
        </MemoryRouter>
      );

    it('walks the attendance card through the full daily lifecycle in the live Home tab UI', async () => {
      const user = userEvent.setup();

      // Step 1: Initial state (Not Clocked In)
      attendanceAPI.getTodayAttendance.mockResolvedValueOnce({
        data: { hasPunchedIn: false, hasPunchedOut: false },
      });
      attendanceAPI.punchOut.mockResolvedValueOnce({
        data: {
          attendanceDate: '2026-10-29',
          punchInTime: '2026-10-29T09:05:00',
          punchOutTime: '2026-10-29T17:20:00',
          status: 'PRESENT',
        },
      });

      const { unmount } = renderHomeTab();

      await waitFor(() =>
        expect(screen.getByText('Not Clocked In')).toBeInTheDocument()
      );
      expect(screen.getByRole('button', { name: /clock in/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /clock out/i })).not.toBeInTheDocument();

      // Step 2: Verify barrier — punch-in requires face + GPS verification.
      // jsdom provides no navigator.geolocation, so the Clock In flow must hit
      // the capability pre-check and never fire a direct punch-in request.
      await user.click(screen.getByRole('button', { name: /clock in/i }));
      expect(
        screen.getByText('Location not supported by this browser.')
      ).toBeInTheDocument();
      expect(attendanceAPI.punchIn).not.toHaveBeenCalled();

      // Step 3: Active state (attendance record exists, punch-in verified earlier)
      unmount();
      attendanceAPI.getTodayAttendance.mockResolvedValueOnce({
        data: {
          hasPunchedIn: true,
          hasPunchedOut: false,
          punchInTime: '2026-10-29T09:05:00',
        },
      });

      renderHomeTab();

      await waitFor(() =>
        expect(screen.getByRole('button', { name: /clock out/i })).toBeInTheDocument()
      );

      // Step 4: User initiates Punch-Out
      await user.click(screen.getByRole('button', { name: /clock out/i }));
      expect(attendanceAPI.punchOut).toHaveBeenCalledTimes(1);

      // Step 5: UI transitions to the completed day state, which still offers
      // "Clock In Again" (a second session) but never Clock Out.
      await waitFor(() => expect(screen.getByText('Completed')).toBeInTheDocument());
      expect(screen.getByRole('button', { name: /clock in again/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /clock out/i })).not.toBeInTheDocument();
    });
  });

  describe('Leave Lifecycle: Application Form -> Pending -> Approved State -> Balance Deduction', () => {
    it('simulates leave application and verifies dynamic quota recalculation on approval', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();

      // Step 1: Faculty applies for 3 days Casual Leave
      const { container, unmount: unmountForm } = render(
        <LeaveApplicationForm onSubmit={onSubmit} onCancel={vi.fn()} />
      );

      const leaveTypeSelect = screen.getByRole('combobox');
      const startDateInput = container.querySelector('input[name="startDate"]');
      const endDateInput = container.querySelector('input[name="endDate"]');
      const reasonInput = container.querySelector('textarea[name="reason"]');
      const submitBtn = screen.getByRole('button', { name: /submit request/i });

      await user.selectOptions(leaveTypeSelect, 'CASUAL');
      await user.type(startDateInput, '2026-11-10');
      await user.type(endDateInput, '2026-11-12'); // 3 days
      await user.type(reasonInput, 'Departmental Research Seminar');

      await user.click(submitBtn);

      expect(onSubmit).toHaveBeenCalledTimes(1);
      expect(onSubmit).toHaveBeenCalledWith({
        leaveType: 'CASUAL',
        startDate: '2026-11-10',
        endDate: '2026-11-12',
        reason: 'Departmental Research Seminar',
      });

      // Application submitted, unmount form view
      unmountForm();

      // Step 2: Render LeaveBalance with the initial PENDING leave
      const pendingLeaveList = [
        {
          id: 501,
          leaveType: 'CASUAL',
          status: 'PENDING',
          startDate: '2026-11-10',
          endDate: '2026-11-12',
        },
      ];
      leaveAPI.getMyLeaves.mockResolvedValueOnce({ data: pendingLeaveList });

      const { unmount: unmountBalance } = render(
        <MemoryRouter>
          <LeaveBalance />
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.queryByText(/calculating leave balance/i)).not.toBeInTheDocument();
      });

      // Pending card shows 3 days; Approved shows 0; All 3 categories show 0 used initially
      expect(screen.getByText('Pending Days')).toBeInTheDocument();
      expect(screen.getByText('3')).toBeInTheDocument(); // 3 pending days
      expect(screen.getAllByText(/0 days used/i)).toHaveLength(3);
      expect(screen.getByText(/12 remaining/i)).toBeInTheDocument();

      // Step 3: HOD approves the leave -> User returns to Leave Balance page
      const approvedLeaveList = [
        {
          id: 501,
          leaveType: 'CASUAL',
          status: 'APPROVED',
          startDate: '2026-11-10',
          endDate: '2026-11-12',
          approvedByName: 'HOD Dr. Babbage',
        },
      ];
      leaveAPI.getMyLeaves.mockResolvedValueOnce({ data: approvedLeaveList });

      // Clean up previous view and mount fresh LeaveBalance view
      unmountBalance();

      render(
        <MemoryRouter>
          <LeaveBalance />
        </MemoryRouter>
      );

      // Now Approved card shows 3 days; Casual quota shows 3 days used and 9 remaining!
      await waitFor(() => {
        expect(screen.queryByText(/calculating leave balance/i)).not.toBeInTheDocument();
      });

      expect(screen.getByText('Approved Days')).toBeInTheDocument();
      expect(screen.getByText(/3 days used/i)).toBeInTheDocument();
      expect(screen.getByText(/9 remaining/i)).toBeInTheDocument();
    });
  });
});
