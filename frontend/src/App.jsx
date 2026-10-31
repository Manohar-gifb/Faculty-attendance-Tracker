import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext';

// Pages
import LoginPage from './pages/LoginPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import SetPasswordPage from './pages/SetPasswordPage';
import ChangePassword from './pages/ChangePassword';
import Dashboard from './pages/Dashboard';
import AdminDashboard from './pages/AdminDashboard';
import DeveloperDashboard from './pages/DeveloperDashboard';
import CreateAccountPage from './pages/CreateAccountPage';
import LeaveManagement from './pages/LeaveManagement';
import ApplyLeave from './pages/ApplyLeave';
import LeaveBalance from './pages/LeaveBalance';
import ProfilePage from './pages/ProfilePage';

// Components
import ProtectedRoute from './components/common/ProtectedRoute';
import AdminRoute from './components/common/AdminRoute';
import DeveloperRoute from './components/common/DeveloperRoute';
import FirstLoginRoute from './components/common/FirstLoginRoute';

function App() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600"></div>
      </div>
    );
  }

  // Helper to determine redirect path based on role
  const getHomePath = (role) => {
    if (role === 'FACULTY') return '/dashboard';
    if (role === 'DEVELOPER') return '/dev';
    return '/admin';
  };

  // If user is logged in but first login, force change password
  // Developer accounts are exempt from forced password change
  const needsFirstLogin = user && user.firstLogin && user.role !== 'DEVELOPER';

  return (
    <Routes>
      {/* Public Routes */}
      <Route
        path="/login"
        element={
          user
            ? (needsFirstLogin
                ? <Navigate to="/change-password" />
                : <Navigate to={getHomePath(user.role)} />)
            : <LoginPage />
        }
      />

      {/* Set Password via email link (new user) */}
      <Route
        path="/set-password"
        element={<SetPasswordPage />}
      />

      {/* Forgot Password (public) — real backend request, no enumeration */}
      <Route
        path="/forgot-password"
        element={<ForgotPasswordPage />}
      />

      {/* Force Change Password (first login only) */}
      <Route
        path="/change-password"
        element={
          <FirstLoginRoute>
            <ChangePassword />
          </FirstLoginRoute>
        }
      />

      {/* Protected Faculty Routes */}
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            <Dashboard />
          </ProtectedRoute>
        }
      />
      <Route
        path="/leaves"
        element={
          <ProtectedRoute>
            <LeaveManagement />
          </ProtectedRoute>
        }
      />
      <Route
        path="/apply-leave"
        element={
          <ProtectedRoute>
            <ApplyLeave />
          </ProtectedRoute>
        }
      />
      <Route
        path="/leave-balance"
        element={
          <ProtectedRoute>
            <LeaveBalance />
          </ProtectedRoute>
        }
      />
      <Route
        path="/profile"
        element={
          <ProtectedRoute>
            <ProfilePage />
          </ProtectedRoute>
        }
      />

      {/* Protected Admin Routes */}
      <Route
        path="/admin"
        element={
          <AdminRoute>
            <AdminDashboard />
          </AdminRoute>
        }
      />
      <Route
        path="/admin/leaves"
        element={
          <AdminRoute>
            <LeaveManagement isAdmin={true} defaultTab="approvals" />
          </AdminRoute>
        }
      />
      <Route
        path="/admin/apply-leave"
        element={
          <AdminRoute>
            <ApplyLeave />
          </AdminRoute>
        }
      />
      <Route
        path="/admin/leave-balance"
        element={
          <AdminRoute>
            <LeaveBalance />
          </AdminRoute>
        }
      />
      <Route
        path="/admin/profile"
        element={
          <AdminRoute>
            <ProfilePage />
          </AdminRoute>
        }
      />

      {/* Protected Developer Routes */}
      <Route
        path="/dev"
        element={
          <DeveloperRoute>
            <DeveloperDashboard />
          </DeveloperRoute>
        }
      />
      <Route
        path="/dev/create-account"
        element={
          <DeveloperRoute>
            <CreateAccountPage />
          </DeveloperRoute>
        }
      />
      <Route
        path="/dev/profile"
        element={
          <DeveloperRoute>
            <ProfilePage />
          </DeveloperRoute>
        }
      />

      {/* Default redirect */}
      <Route
        path="*"
        element={
          <Navigate to={user ? (needsFirstLogin ? '/change-password' : getHomePath(user.role)) : '/login'} />
        }
      />
    </Routes>
  );
}

export default App;
