import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import {
  HiHome,
  HiCalendar,
  HiUser,
  HiLogout,
  HiChartBar,
  HiClipboardCheck,
} from 'react-icons/hi';
import { imageSrc } from '../../services/api';
import logo from '../../assets/faculty_sync_tracker_logo.png';

export default function Sidebar({ isAdmin = false }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const facultyLinks = [
    { to: '/dashboard', icon: HiHome, label: 'Dashboard' },
    { to: '/leaves', icon: HiCalendar, label: 'Leave Management' },
    { to: '/profile', icon: HiUser, label: 'My Profile' },
  ];

  const adminLinks = [
    { to: '/admin', icon: HiChartBar, label: 'Dashboard' },
    { to: '/admin/leaves', icon: HiClipboardCheck, label: 'Leave Approvals' },
    { to: '/admin/profile', icon: HiUser, label: 'My Profile' },
  ];

  const links = isAdmin ? adminLinks : facultyLinks;

  return (
    <div className="w-64 bg-white border-r border-gray-200 min-h-screen flex flex-col">
      {/* Logo */}
      <div className="p-5 border-b border-gray-200 flex flex-col items-center text-center">
        <img
          src={logo}
          alt="Faculty Sync Tracker Logo"
          className="h-12 w-auto object-contain mb-2"
        />
        <h1 className="text-lg font-bold text-gray-900 leading-tight">Faculty Sync Tracker</h1>
        <p className="text-xs text-gray-500 mt-0.5">Attendance &amp; Leave Portal</p>
      </div>

      {/* User Info */}
      <div className="p-4 border-b border-gray-200">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-primary-100 flex items-center justify-center overflow-hidden">
            {user?.profilePictureUrl ? (
              <>
                <img
                  src={imageSrc(user.profilePictureUrl)}
                  alt={user.fullName || "User"}
                  className="w-full h-full object-cover"
                  onError={(e) => {
                    e.currentTarget.style.display = 'none';
                    if (e.currentTarget.nextSibling) e.currentTarget.nextSibling.style.display = 'inline';
                  }}
                />
                <span className="text-primary-600 font-semibold text-base" style={{ display: 'none' }}>
                  {user?.fullName?.charAt(0) || 'U'}
                </span>
              </>
            ) : (
              <span className="text-primary-600 font-semibold text-base">
                {user?.fullName?.charAt(0) || 'U'}
              </span>
            )}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-base font-semibold text-gray-900 truncate">{user?.fullName}</p>
            <p className="text-sm text-gray-500 truncate">{user?.role}</p>
          </div>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 p-4 space-y-1">
        {links.map((link) => (
          <NavLink
            key={link.to}
            to={link.to}
            end={link.to === '/admin'}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-lg text-base font-medium transition-colors ${
                isActive
                  ? 'bg-primary-50 text-primary-700 font-semibold'
                  : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
              }`
            }
          >
            <link.icon className="w-5 h-5" />
            {link.label}
          </NavLink>
        ))}
      </nav>

      {/* Logout Button */}
      <div className="p-4 border-t border-gray-200">
        <button
          onClick={handleLogout}
          className="flex items-center gap-3 w-full px-3 py-2.5 rounded-lg text-base font-medium text-red-600 hover:bg-red-50 transition-colors"
        >
          <HiLogout className="w-5 h-5" />
          Sign Out
        </button>
      </div>
    </div>
  );
}
