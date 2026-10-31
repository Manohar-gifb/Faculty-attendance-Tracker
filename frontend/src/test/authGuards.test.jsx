import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import ProtectedRoute from '../components/common/ProtectedRoute';
import AdminRoute from '../components/common/AdminRoute';
import DeveloperRoute from '../components/common/DeveloperRoute';
import { useAuth } from '../context/AuthContext';

// Mock useAuth for direct route guard tests
vi.mock('../context/AuthContext', async () => {
  const actual = await vi.importActual('../context/AuthContext');
  return {
    ...actual,
    useAuth: vi.fn(),
  };
});

describe('Authentication & Authorization Route Guards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const renderWithRouter = (ui, initialPath = '/target') => {
    return render(
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/login" element={<div>Login Page</div>} />
          <Route path="/dashboard" element={<div>Faculty Dashboard</div>} />
          <Route path="/admin" element={<div>Admin Dashboard</div>} />
          <Route path="/target" element={ui} />
        </Routes>
      </MemoryRouter>
    );
  };

  describe('ProtectedRoute', () => {
    it('redirects to /login when user is unauthenticated', () => {
      useAuth.mockReturnValue({
        user: null,
        loading: false,
      });

      renderWithRouter(
        <ProtectedRoute>
          <div>Protected Content</div>
        </ProtectedRoute>
      );

      expect(screen.queryByText('Protected Content')).not.toBeInTheDocument();
      expect(screen.getByText('Login Page')).toBeInTheDocument();
    });

    it('renders loading spinner when auth loading is true', () => {
      useAuth.mockReturnValue({
        user: null,
        loading: true,
      });

      const { container } = renderWithRouter(
        <ProtectedRoute>
          <div>Protected Content</div>
        </ProtectedRoute>
      );

      expect(screen.queryByText('Protected Content')).not.toBeInTheDocument();
      expect(container.querySelector('.animate-spin')).toBeInTheDocument();
    });

    it('renders child content when user is authenticated', () => {
      useAuth.mockReturnValue({
        user: { id: 1, role: 'FACULTY', email: 'faculty@college.edu' },
        loading: false,
      });

      renderWithRouter(
        <ProtectedRoute>
          <div>Protected Content</div>
        </ProtectedRoute>
      );

      expect(screen.getByText('Protected Content')).toBeInTheDocument();
      expect(screen.queryByText('Login Page')).not.toBeInTheDocument();
    });
  });

  describe('AdminRoute', () => {
    it('redirects to /login when user is unauthenticated', () => {
      useAuth.mockReturnValue({
        user: null,
        loading: false,
        isAdmin: () => false,
      });

      renderWithRouter(
        <AdminRoute>
          <div>Admin Only Content</div>
        </AdminRoute>
      );

      expect(screen.queryByText('Admin Only Content')).not.toBeInTheDocument();
      expect(screen.getByText('Login Page')).toBeInTheDocument();
    });

    it('redirects regular faculty to /dashboard when role is not admin', () => {
      useAuth.mockReturnValue({
        user: { id: 2, role: 'FACULTY', email: 'faculty@college.edu' },
        loading: false,
        isAdmin: () => false,
      });

      renderWithRouter(
        <AdminRoute>
          <div>Admin Only Content</div>
        </AdminRoute>
      );

      expect(screen.queryByText('Admin Only Content')).not.toBeInTheDocument();
      expect(screen.getByText('Faculty Dashboard')).toBeInTheDocument();
    });

    it('renders admin content for permitted admin roles (HOD, TPO, PRINCIPAL, VC)', () => {
      useAuth.mockReturnValue({
        user: { id: 3, role: 'HOD', email: 'hod@college.edu' },
        loading: false,
        isAdmin: () => true,
      });

      renderWithRouter(
        <AdminRoute>
          <div>Admin Only Content</div>
        </AdminRoute>
      );

      expect(screen.getByText('Admin Only Content')).toBeInTheDocument();
      expect(screen.queryByText('Faculty Dashboard')).not.toBeInTheDocument();
    });
  });

  describe('DeveloperRoute', () => {
    it('redirects to /login when user is unauthenticated', () => {
      useAuth.mockReturnValue({
        user: null,
        loading: false,
        isDeveloper: () => false,
      });

      renderWithRouter(
        <DeveloperRoute>
          <div>Developer Tools</div>
        </DeveloperRoute>
      );

      expect(screen.queryByText('Developer Tools')).not.toBeInTheDocument();
      expect(screen.getByText('Login Page')).toBeInTheDocument();
    });

    it('redirects regular faculty to /dashboard when accessing developer route', () => {
      useAuth.mockReturnValue({
        user: { id: 4, role: 'FACULTY', email: 'faculty@college.edu' },
        loading: false,
        isDeveloper: () => false,
      });

      renderWithRouter(
        <DeveloperRoute>
          <div>Developer Tools</div>
        </DeveloperRoute>
      );

      expect(screen.queryByText('Developer Tools')).not.toBeInTheDocument();
      expect(screen.getByText('Faculty Dashboard')).toBeInTheDocument();
    });

    it('redirects admin user to /admin when accessing developer route', () => {
      useAuth.mockReturnValue({
        user: { id: 5, role: 'HOD', email: 'hod@college.edu' },
        loading: false,
        isDeveloper: () => false,
      });

      renderWithRouter(
        <DeveloperRoute>
          <div>Developer Tools</div>
        </DeveloperRoute>
      );

      expect(screen.queryByText('Developer Tools')).not.toBeInTheDocument();
      expect(screen.getByText('Admin Dashboard')).toBeInTheDocument();
    });

    it('renders developer tools when user is DEVELOPER', () => {
      useAuth.mockReturnValue({
        user: { id: 6, role: 'DEVELOPER', email: 'dev@college.edu' },
        loading: false,
        isDeveloper: () => true,
      });

      renderWithRouter(
        <DeveloperRoute>
          <div>Developer Tools</div>
        </DeveloperRoute>
      );

      expect(screen.getByText('Developer Tools')).toBeInTheDocument();
    });
  });

  describe('AuthContext Storage & Session Resilience', () => {
    it('purges obsolete developer email admin@college.edu on initialization', () => {
      // Unmock useAuth to test actual AuthProvider behavior
      const actualAuth = vi.importActual('../context/AuthContext');
      return actualAuth.then(({ AuthProvider: RealAuthProvider, useAuth: realUseAuth }) => {
        localStorage.setItem('token', 'fake-token');
        localStorage.setItem('user', JSON.stringify({
          role: 'DEVELOPER',
          email: 'admin@college.edu',
        }));

        const Consumer = () => {
          const { user, token } = realUseAuth();
          return <div>User: {user ? user.email : 'None'}, Token: {token || 'None'}</div>;
        };

        render(
          <RealAuthProvider>
            <Consumer />
          </RealAuthProvider>
        );

        expect(screen.getByText(/User: None/)).toBeInTheDocument();
        expect(screen.getByText(/Token: None/)).toBeInTheDocument();
        expect(localStorage.getItem('token')).toBeNull();
        expect(localStorage.getItem('user')).toBeNull();
      });
    });

    it('gracefully handles corrupted JSON in localStorage', () => {
      const actualAuth = vi.importActual('../context/AuthContext');
      return actualAuth.then(({ AuthProvider: RealAuthProvider, useAuth: realUseAuth }) => {
        localStorage.setItem('token', 'fake-token');
        localStorage.setItem('user', '{malformed json ...');

        const Consumer = () => {
          const { user, token } = realUseAuth();
          return <div>User: {user ? user.email : 'None'}, Token: {token || 'None'}</div>;
        };

        render(
          <RealAuthProvider>
            <Consumer />
          </RealAuthProvider>
        );

        expect(screen.getByText(/User: None/)).toBeInTheDocument();
        expect(localStorage.getItem('token')).toBeNull();
      });
    });
  });
});
