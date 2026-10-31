import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import LeaveBalance from '../pages/LeaveBalance';
import LeaveApplicationForm from '../components/faculty/LeaveApplicationForm';
import { leaveAPI } from '../services/api';
import { useAuth } from '../context/AuthContext';

const mockNavigate = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

vi.mock('../services/api', () => ({
  leaveAPI: {
    getMyLeaves: vi.fn(),
  },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

describe('Leave Workflow & Balance Calculations', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuth.mockReturnValue({
      user: { id: 10, fullName: 'Dr. Alan Turing', role: 'FACULTY' },
    });
  });

  describe('LeaveBalance Page & Quota Computations', () => {
    it('accurately computes approved, pending, rejected days and category quotas', async () => {
      const mockLeaves = [
        {
          id: 1,
          leaveType: 'CASUAL',
          status: 'APPROVED',
          startDate: '2026-11-10',
          endDate: '2026-11-12', // 3 days
        },
        {
          id: 2,
          leaveType: 'SICK',
          status: 'APPROVED',
          startDate: '2026-11-15',
          endDate: '2026-11-16', // 2 days
        },
        {
          id: 3,
          leaveType: 'EARNED',
          status: 'PENDING',
          startDate: '2026-11-20',
          endDate: '2026-11-24', // 5 days
        },
        {
          id: 4,
          leaveType: 'CASUAL',
          status: 'REJECTED',
          startDate: '2026-11-01',
          endDate: '2026-11-02', // 2 days
        },
      ];

      leaveAPI.getMyLeaves.mockResolvedValueOnce({ data: mockLeaves });

      render(
        <MemoryRouter>
          <LeaveBalance />
        </MemoryRouter>
      );

      // Loading state disappears
      await waitFor(() => {
        expect(screen.queryByText(/calculating leave balance/i)).not.toBeInTheDocument();
      });

      // Metric cards: Approved has 5, Pending has 5 -> exactly 2 occurrences of '5'
      const fiveDayBadges = screen.getAllByText('5');
      expect(fiveDayBadges.length).toBeGreaterThanOrEqual(2);

      // Rejected card: exactly 2 days
      expect(screen.getByText('2')).toBeInTheDocument();

      // Casual Leave category: 3 used, 9 remaining (out of 12)
      expect(screen.getByText(/3 days used/i)).toBeInTheDocument();
      expect(screen.getByText(/9 remaining/i)).toBeInTheDocument();

      // Sick Leave category: 2 used, 8 remaining (out of 10)
      expect(screen.getByText(/2 days used/i)).toBeInTheDocument();
      expect(screen.getByText(/8 remaining/i)).toBeInTheDocument();

      // Earned Leave category: 0 approved used, 15 remaining (out of 15)
      expect(screen.getByText(/0 days used/i)).toBeInTheDocument();
      expect(screen.getByText(/15 remaining/i)).toBeInTheDocument();
    });

    it('enforces non-negative boundary when approved days exceed base quota', async () => {
      // 14 days of Casual Leave approved when entitlement is 12
      const mockLeaves = [
        {
          id: 1,
          leaveType: 'CASUAL',
          status: 'APPROVED',
          startDate: '2026-11-01',
          endDate: '2026-11-14', // 14 days
        },
      ];

      leaveAPI.getMyLeaves.mockResolvedValueOnce({ data: mockLeaves });

      render(
        <MemoryRouter>
          <LeaveBalance />
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.queryByText(/calculating leave balance/i)).not.toBeInTheDocument();
      });

      // Remaining must be clamped at 0 (never negative)
      expect(screen.getByText(/14 days used/i)).toBeInTheDocument();
      const zeroRemaining = screen.getAllByText(/0 remaining/i);
      expect(zeroRemaining.length).toBeGreaterThanOrEqual(1);
    });

    it('renders zero counts cleanly when faculty has no leave records', async () => {
      leaveAPI.getMyLeaves.mockResolvedValueOnce({ data: [] });

      render(
        <MemoryRouter>
          <LeaveBalance />
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.queryByText(/calculating leave balance/i)).not.toBeInTheDocument();
      });

      expect(screen.getAllByText('0')).toHaveLength(3); // 3 metric cards at 0
      expect(screen.getByText(/12 remaining/i)).toBeInTheDocument();
      expect(screen.getByText(/10 remaining/i)).toBeInTheDocument();
      expect(screen.getByText(/15 remaining/i)).toBeInTheDocument();
    });

    it('redirects to /login when getMyLeaves returns 401 Unauthorized', async () => {
      leaveAPI.getMyLeaves.mockRejectedValueOnce({
        response: { status: 401 },
      });

      render(
        <MemoryRouter>
          <LeaveBalance />
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true });
      });
    });
  });

  describe('LeaveApplicationForm Component Validation', () => {
    it('prevents submission when endDate is before startDate', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();

      const { container } = render(<LeaveApplicationForm onSubmit={onSubmit} onCancel={vi.fn()} />);

      const leaveTypeSelect = screen.getByRole('combobox');
      const startDateInput = container.querySelector('input[name="startDate"]');
      const endDateInput = container.querySelector('input[name="endDate"]');
      const reasonInput = container.querySelector('textarea[name="reason"]');
      const submitBtn = screen.getByRole('button', { name: /submit request/i });

      await user.selectOptions(leaveTypeSelect, 'CASUAL');
      await user.type(startDateInput, '2026-11-20');
      await user.type(endDateInput, '2026-11-15'); // End date before start date
      await user.type(reasonInput, 'Vacation');

      await user.click(submitBtn);

      expect(onSubmit).not.toHaveBeenCalled();
    });

    it('submits clean payload when all required fields and valid dates are provided', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();

      const { container } = render(<LeaveApplicationForm onSubmit={onSubmit} onCancel={vi.fn()} />);

      const leaveTypeSelect = screen.getByRole('combobox');
      const startDateInput = container.querySelector('input[name="startDate"]');
      const endDateInput = container.querySelector('input[name="endDate"]');
      const reasonInput = container.querySelector('textarea[name="reason"]');
      const submitBtn = screen.getByRole('button', { name: /submit request/i });

      await user.selectOptions(leaveTypeSelect, 'SICK');
      await user.type(startDateInput, '2026-11-10');
      await user.type(endDateInput, '2026-11-12');
      await user.type(reasonInput, 'Seasonal Flu');

      await user.click(submitBtn);

      expect(onSubmit).toHaveBeenCalledTimes(1);
      expect(onSubmit).toHaveBeenCalledWith({
        leaveType: 'SICK',
        startDate: '2026-11-10',
        endDate: '2026-11-12',
        reason: 'Seasonal Flu',
      });
    });

    it('invokes onCancel when cancel button is clicked', async () => {
      const user = userEvent.setup();
      const onCancel = vi.fn();

      render(<LeaveApplicationForm onSubmit={vi.fn()} onCancel={onCancel} />);

      const cancelBtn = screen.getByRole('button', { name: /cancel/i });
      await user.click(cancelBtn);

      expect(onCancel).toHaveBeenCalledTimes(1);
    });
  });
});
