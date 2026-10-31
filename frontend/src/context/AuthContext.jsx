import { createContext, useContext, useState, useEffect } from 'react';
import { authAPI } from '../services/api';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(null);
  const [loading, setLoading] = useState(true);

  // Load user from localStorage on mount
  useEffect(() => {
    const storedToken = localStorage.getItem('token');
    const storedUser = localStorage.getItem('user');

    if (storedToken && storedUser) {
      try {
        const parsed = JSON.parse(storedUser);
        // If developer account in localStorage still has obsolete email admin@college.edu, clear and prompt login
        if (parsed?.role === 'DEVELOPER' && parsed?.email === 'admin@college.edu') {
          localStorage.removeItem('token');
          localStorage.removeItem('user');
          setToken(null);
          setUser(null);
          setLoading(false);
          return;
        }
        setToken(storedToken);
        setUser(parsed);
      } catch (e) {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
      }
    }
    setLoading(false);
  }, []);

  const login = async (email, password) => {
    try {
      const response = await authAPI.login(email, password);
      const data = response.data;

      // Store token and user data
      localStorage.setItem('token', data.token);
      localStorage.setItem('user', JSON.stringify(data));

      setToken(data.token);
      setUser(data);

      // Return success with firstLogin flag
      return {
        success: true,
        user: data,
        firstLogin: data.firstLogin || false,
      };
    } catch (error) {
      const message = error.response?.data?.message || 'Login failed. Please try again.';
      return { success: false, message };
    }
  };

  const completeFirstLogin = async (newPassword, confirmPassword) => {
    try {
      await authAPI.changePasswordFirstLogin({
        newPassword,
        confirmPassword,
      });

      // Update local user to mark first login complete
      const updatedUser = { ...user, firstLogin: false };
      localStorage.setItem('user', JSON.stringify(updatedUser));
      setUser(updatedUser);

      return { success: true };
    } catch (error) {
      const message = error.response?.data?.message || 'Failed to change password.';
      return { success: false, message };
    }
  };

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setToken(null);
    setUser(null);
  };

  const updateUser = (updatedData) => {
    const newUser = { ...user, ...updatedData };
    localStorage.setItem('user', JSON.stringify(newUser));
    setUser(newUser);
  };

  const isAdmin = () => {
    return user && ['VC', 'PRINCIPAL', 'TPO', 'HOD'].includes(user.role);
  };

  const isDeveloper = () => {
    return user && user.role === 'DEVELOPER';
  };

  const isFirstLogin = () => {
    return user && user.firstLogin === true;
  };

  const isHOD = () => {
    return user && ['DEVELOPER', 'VC', 'PRINCIPAL', 'TPO', 'HOD'].includes(user.role);
  };

  const value = {
    user,
    token,
    loading,
    login,
    logout,
    updateUser,
    completeFirstLogin,
    isAdmin,
    isDeveloper,
    isFirstLogin,
    isHOD,
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
