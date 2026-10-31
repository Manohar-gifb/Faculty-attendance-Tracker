import { useAuth } from '../context/AuthContext';
import LeaveManagement from './LeaveManagement';

export default function ApplyLeave() {
  const { user } = useAuth();
  const isAdmin = user?.role && ['ADMIN', 'VC', 'PRINCIPAL', 'TPO', 'HOD'].includes(user.role);

  return <LeaveManagement isAdmin={isAdmin} defaultTab="apply" />;
}
