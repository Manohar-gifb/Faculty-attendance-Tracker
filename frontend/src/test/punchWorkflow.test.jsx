import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import HomeTab from '../components/faculty/HomeTab';
import { attendanceAPI, teamAPI } from '../services/api';

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
}));

const renderHomeTab = (props = {}) =>
  render(
    <MemoryRouter>
      <HomeTab {...props} />
    </MemoryRouter>
  );

describe('Attendance Punch Workflow & States (HomeTab)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    attendanceAPI.punchIn.mockResolvedValue({ data: { message: 'Success' } });
    attendanceAPI.punchOut.mockResolvedValue({ data: { message: 'Success' } });
    attendanceAPI.getHistory.mockResolvedValue({ data: [] });
    teamAPI.getDepartmentFaculty.mockResolvedValue({ data: [] });
  });

  describe('Visual and Status States', () => {
    it('renders "Not Clocked In" state with Clock In button when user has not punched in', async () => {
      attendanceAPI.getTodayAttendance.mockResolvedValue({
        data: { hasPunchedIn: false, hasPunchedOut: false },
      });

      renderHomeTab();

      await waitFor(() =>
        expect(screen.getByText('Not Clocked In')).toBeInTheDocument()
      );
      expect(screen.getByRole('button', { name: /clock in/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /clock out/i })).not.toBeInTheDocument();
    });

    it('renders "Working" state with Clock Out button when the user is already clocked in', async () => {
      attendanceAPI.getTodayAttendance.mockResolvedValue({
        data: {
          hasPunchedIn: true,
          hasPunchedOut: false,
          punchInTime: '2026-10-29T09:30:00',
        },
      });

      renderHomeTab();

      await waitFor(() => expect(screen.getByText('Working')).toBeInTheDocument());
      expect(screen.getByRole('button', { name: /clock out/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /clock in/i })).not.toBeInTheDocument();
    });

    it('renders "Completed" state for the day once punch-out time is recorded', async () => {
      attendanceAPI.getTodayAttendance.mockResolvedValue({
        data: {
          hasPunchedIn: true,
          hasPunchedOut: true,
          punchInTime: '2026-10-29T09:00:00',
          punchOutTime: '2026-10-29T16:30:00',
          workingHours: 7.5,
        },
      });

      renderHomeTab();

      await waitFor(() => expect(screen.getByText('Completed')).toBeInTheDocument());
      // After a completed day the card still offers "Clock In Again" (a second
      // session) — but never the Clock Out action.
      expect(screen.getByRole('button', { name: /clock in again/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /clock out/i })).not.toBeInTheDocument();
    });
  });

  describe('Verification Barrier (Clock In gating)', () => {
    it('blocks direct punch-in attendance requests without camera and GPS support', async () => {
      // jsdom has no navigator.geolocation/mediaDevices: the Clock In flow must
      // hit the capability pre-check BEFORE any verification or API work and
      // surface actionable guidance instead of firing a verification request.
      attendanceAPI.getTodayAttendance.mockResolvedValue({
        data: { hasPunchedIn: false, hasPunchedOut: false },
      });

      renderHomeTab();
      await waitFor(() =>
        expect(screen.getByRole('button', { name: /clock in/i })).toBeInTheDocument()
      );

      const user = userEvent.setup();
      await user.click(screen.getByRole('button', { name: /clock in/i }));

      expect(
        screen.getByText('Location not supported by this browser.')
      ).toBeInTheDocument();
      // Punch-in may NEVER be called without a passed verification ticket.
      expect(attendanceAPI.punchIn).not.toHaveBeenCalled();
    });
  });

  describe('User Interactions and API Handling', () => {
    it('executes punch-out flow and transitions the card to the completed state', async () => {
      const user = userEvent.setup();
      attendanceAPI.getTodayAttendance.mockResolvedValue({
        data: {
          hasPunchedIn: true,
          hasPunchedOut: false,
          punchInTime: '2026-10-29T09:00:00',
        },
      });
      attendanceAPI.punchOut.mockResolvedValueOnce({
        data: {
          attendanceDate: '2026-10-29',
          punchInTime: '2026-10-29T09:00:00',
          punchOutTime: '2026-10-29T17:05:00',
          status: 'PRESENT',
        },
      });

      renderHomeTab();

      await waitFor(() =>
        expect(screen.getByRole('button', { name: /clock out/i })).toBeInTheDocument()
      );
      await user.click(screen.getByRole('button', { name: /clock out/i }));

      expect(attendanceAPI.punchOut).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(screen.getByText('Completed')).toBeInTheDocument());
    });

    it('surfaces the API error message without faking completion when punch-out fails', async () => {
      const user = userEvent.setup();
      attendanceAPI.getTodayAttendance.mockResolvedValue({
        data: {
          hasPunchedIn: true,
          hasPunchedOut: false,
          punchInTime: '2026-10-29T09:00:00',
        },
      });
      attendanceAPI.punchOut.mockRejectedValueOnce({
        response: { data: { message: 'Already punched out for today' } },
      });

      renderHomeTab();

      await waitFor(() =>
        expect(screen.getByRole('button', { name: /clock out/i })).toBeInTheDocument()
      );
      await user.click(screen.getByRole('button', { name: /clock out/i }));

      await waitFor(() =>
        expect(screen.getByText('Already punched out for today')).toBeInTheDocument()
      );
      // The card must NOT show the completed state after a failed punch-out.
      expect(screen.queryByText('Completed')).not.toBeInTheDocument();
    });
  });

  describe('Faculty List Filtering', () => {
    it('filters the faculty list by the provided search query', async () => {
      teamAPI.getDepartmentFaculty.mockResolvedValue({
        data: [
          { id: 1, fullName: 'Alice Newton', employeeId: 'FAC101' },
          { id: 2, fullName: 'Bob Pascal', employeeId: 'FAC102' },
        ],
      });
      attendanceAPI.getTodayAttendance.mockResolvedValue({
        data: { hasPunchedIn: false, hasPunchedOut: false },
      });

      renderHomeTab({ searchQuery: 'alice' });

      await waitFor(() =>
        expect(screen.getByText('Alice Newton')).toBeInTheDocument()
      );
      expect(screen.queryByText('Bob Pascal')).not.toBeInTheDocument();
    });
  });
});
