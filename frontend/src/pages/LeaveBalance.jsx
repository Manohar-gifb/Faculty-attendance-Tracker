import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { leaveAPI } from '../services/api';
import { useAuth } from '../context/AuthContext';

// React Icons
import {
  LuArrowLeft,
  LuCalendarDays,
  LuClock,
  LuCheckCircle2,
  LuXCircle,
  LuPlus,
  LuSparkles
} from 'react-icons/lu';

/* =========================================================
   LEAVE TYPES QUOTAS
========================================================= */

const LEAVE_TYPES = Object.freeze([
  {
    key: 'CASUAL',
    name: 'Casual Leave',
    shortName: 'CL',
    total: 12,
    gradient: 'from-blue-500 to-cyan-500',
    bgLight: 'bg-blue-50/60',
    borderLight: 'border-blue-200/60',
    textColor: 'text-blue-700',
    barColor: 'bg-blue-500',
  },
  {
    key: 'SICK',
    name: 'Sick Leave',
    shortName: 'SL',
    total: 10,
    gradient: 'from-amber-500 to-orange-500',
    bgLight: 'bg-amber-50/60',
    borderLight: 'border-amber-200/60',
    textColor: 'text-amber-700',
    barColor: 'bg-amber-500',
  },
  {
    key: 'EARNED',
    name: 'Earned Leave',
    shortName: 'EL',
    total: 15,
    gradient: 'from-emerald-500 to-teal-500',
    bgLight: 'bg-emerald-50/60',
    borderLight: 'border-emerald-200/60',
    textColor: 'text-emerald-700',
    barColor: 'bg-emerald-500',
  },
]);

/* =========================================================
   HELPERS
========================================================= */

const normalizeStatus = (status) => {
  const value = String(status || '').trim().toUpperCase();
  if (value === 'APPROVED') return 'APPROVED';
  if (value === 'REJECTED') return 'REJECTED';
  return 'PENDING';
};

const normalizeLeaveType = (leaveType) => {
  return String(leaveType || '').trim().toUpperCase().replace(/\s+/g, '_').replace(/-/g, '_');
};

const calculateLeaveDays = (fromDate, toDate) => {
  if (!fromDate || !toDate) return 0;
  const start = new Date(`${fromDate}T00:00:00`);
  const end = new Date(`${toDate}T00:00:00`);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 0;
  if (end < start) return 0;
  return Math.floor((end.getTime() - start.getTime()) / (24 * 60 * 60 * 1000)) + 1;
};

const formatDate = (value) => {
  if (!value) return '--';
  const date = new Date(`${value}T00:00:00`);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
};

/* =========================================================
   COMPONENT
========================================================= */

export default function LeaveBalance() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const isAdmin = user?.role && ['ADMIN', 'VC', 'PRINCIPAL', 'TPO', 'HOD'].includes(user.role);

  const [leaveData, setLeaveData] = useState([]);
  const [loading, setLoading] = useState(true);

  /* Load Leave Data */
  useEffect(() => {
    let mounted = true;

    const loadLeaveData = async () => {
      try {
        setLoading(true);
        const res = await leaveAPI.getMyLeaves();
        const data = Array.isArray(res.data) ? res.data : [];
        if (mounted) setLeaveData(data);
      } catch (error) {
        if (error.response?.status === 401 || error.response?.status === 403) {
          navigate('/login', { replace: true });
          return;
        }
        if (mounted) setLeaveData([]);
      } finally {
        if (mounted) setLoading(false);
      }
    };

    loadLeaveData();
    return () => {
      mounted = false;
    };
  }, [navigate]);

  /* Computed Values */
  const approvedLeaves = useMemo(
    () => leaveData.filter((l) => normalizeStatus(l.status) === 'APPROVED'),
    [leaveData]
  );

  const pendingLeaves = useMemo(
    () => leaveData.filter((l) => normalizeStatus(l.status) === 'PENDING'),
    [leaveData]
  );

  const rejectedLeaves = useMemo(
    () => leaveData.filter((l) => normalizeStatus(l.status) === 'REJECTED'),
    [leaveData]
  );

  const totalApprovedDays = useMemo(
    () => approvedLeaves.reduce((sum, l) => sum + calculateLeaveDays(l.startDate, l.endDate), 0),
    [approvedLeaves]
  );

  const totalPendingDays = useMemo(
    () => pendingLeaves.reduce((sum, l) => sum + calculateLeaveDays(l.startDate, l.endDate), 0),
    [pendingLeaves]
  );

  const totalRejectedDays = useMemo(
    () => rejectedLeaves.reduce((sum, l) => sum + calculateLeaveDays(l.startDate, l.endDate), 0),
    [rejectedLeaves]
  );

  const getLeaveTypeUsed = (leaveType) => {
    return approvedLeaves.reduce((sum, l) => {
      if (normalizeLeaveType(l.leaveType) !== leaveType) return sum;
      return sum + calculateLeaveDays(l.startDate, l.endDate);
    }, 0);
  };

  const handleApplyLeave = () => {
    navigate(isAdmin ? '/admin/leaves?tab=apply' : '/leaves?tab=apply');
  };

  const handleBack = () => {
    navigate(isAdmin ? '/admin/leaves' : '/leaves');
  };

  return (
    <div className="bg-[#f0f4f8] min-h-screen flex items-center justify-center p-4 sm:p-8 text-gray-800 antialiased selection:bg-teal-200 selection:text-teal-900">
      {/* Main Container */}
      <main className="w-full max-w-5xl bg-white rounded-[2rem] shadow-[0_20px_50px_-12px_rgba(0,0,0,0.06)] overflow-hidden border border-white/40 backdrop-blur-xl">
        <div className="p-6 sm:p-12">
          {/* Header */}
          <header className="flex flex-col sm:flex-row justify-between items-start sm:items-center mb-8 gap-4">
            <div className="flex items-center gap-4">
              <button
                type="button"
                onClick={handleBack}
                title="Back to Leave Management"
                className="p-2.5 rounded-full hover:bg-gray-100 text-gray-500 hover:text-gray-800 transition-colors border border-gray-200/60 shadow-sm"
              >
                <LuArrowLeft className="w-5 h-5" />
              </button>
              <div>
                <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-gray-900">
                  Leave Balance
                </h1>
                <p className="text-xs sm:text-sm text-gray-400 font-medium">
                  {user?.fullName || 'Faculty Member'} • Annual Entitlement & Usage
                </p>
              </div>
            </div>

            <div className="flex items-center gap-3 w-full sm:w-auto justify-end">
              <button
                type="button"
                onClick={handleApplyLeave}
                className="px-6 py-2.5 rounded-xl bg-[#48c6b6] text-white font-bold text-xs hover:bg-[#3baea0] transition-all shadow-[0_8px_16px_-4px_rgba(72,198,182,0.4)] flex items-center gap-2"
              >
                <LuPlus className="w-4 h-4" />
                Apply Leave
              </button>
            </div>
          </header>

          {/* Top 3 Metric Cards */}
          <section className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-8">
            <div className="bg-gradient-to-br from-emerald-50/90 to-emerald-100/30 border border-emerald-200/60 rounded-2xl p-4 sm:p-5 shadow-sm">
              <div className="flex items-center justify-between mb-2">
                <span className="text-[13px] font-bold tracking-wider text-emerald-600 uppercase">
                  Approved Days
                </span>
                <span className="p-2 rounded-xl bg-emerald-500/10 text-emerald-600">
                  <LuCheckCircle2 className="w-4 h-4" />
                </span>
              </div>
              <div className="text-3xl font-extrabold text-emerald-950 tracking-tight">
                {totalApprovedDays}
                <span className="text-sm font-medium text-emerald-600 ml-1">Days</span>
              </div>
              <p className="mt-2 text-[13.5px] font-medium text-emerald-600">Deducted from balance</p>
            </div>

            <div className="bg-gradient-to-br from-amber-50/90 to-amber-100/30 border border-amber-200/60 rounded-2xl p-4 sm:p-5 shadow-sm">
              <div className="flex items-center justify-between mb-2">
                <span className="text-[13px] font-bold tracking-wider text-amber-600 uppercase">
                  Pending Days
                </span>
                <span className="p-2 rounded-xl bg-amber-500/10 text-amber-600">
                  <LuClock className="w-4 h-4" />
                </span>
              </div>
              <div className="text-3xl font-extrabold text-amber-950 tracking-tight">
                {totalPendingDays}
                <span className="text-sm font-medium text-amber-600 ml-1">Days</span>
              </div>
              <p className="mt-2 text-[13.5px] font-medium text-amber-600">Awaiting HOD approval</p>
            </div>

            <div className="bg-gradient-to-br from-rose-50/90 to-rose-100/30 border border-rose-200/60 rounded-2xl p-4 sm:p-5 shadow-sm">
              <div className="flex items-center justify-between mb-2">
                <span className="text-[13px] font-bold tracking-wider text-rose-600 uppercase">
                  Rejected Days
                </span>
                <span className="p-2 rounded-xl bg-rose-500/10 text-rose-600">
                  <LuXCircle className="w-4 h-4" />
                </span>
              </div>
              <div className="text-3xl font-extrabold text-rose-950 tracking-tight">
                {totalRejectedDays}
                <span className="text-sm font-medium text-rose-600 ml-1">Days</span>
              </div>
              <p className="mt-2 text-[13.5px] font-medium text-rose-600">Not deducted</p>
            </div>
          </section>

          {/* Leave Type Breakdown Cards */}
          <section className="mb-10">
            <h2 className="text-base font-bold text-gray-900 tracking-wide uppercase mb-4 flex items-center gap-2">
              <LuSparkles className="text-teal-500 w-4 h-4" />
              Category Breakdown
            </h2>

            {loading ? (
              <div className="py-12 text-center">
                <div className="w-8 h-8 border-4 border-teal-500 border-t-transparent rounded-full animate-spin mx-auto mb-2"></div>
                <p className="text-xs text-gray-500">Calculating leave balance...</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
                {LEAVE_TYPES.map((type) => {
                  const used = getLeaveTypeUsed(type.key);
                  const remaining = Math.max(type.total - used, 0);
                  const percentUsed = type.total > 0 ? Math.min((used / type.total) * 100, 100) : 0;

                  return (
                    <div
                      key={type.key}
                      className={`p-5 rounded-2xl ${type.bgLight} border ${type.borderLight} shadow-sm flex flex-col justify-between`}
                    >
                      <div>
                        <div className="flex items-center justify-between mb-3">
                          <span className="px-2.5 py-1 rounded-lg bg-white/80 font-bold text-xs text-gray-700 shadow-xs border border-gray-100">
                            {type.shortName}
                          </span>
                          <span className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                            Available
                          </span>
                        </div>
                        <h3 className="text-sm font-bold text-gray-800 mb-1">{type.name}</h3>
                        <div className="text-3xl font-black text-gray-900 mb-4">
                          {remaining}
                          <span className="text-[13.5px] font-semibold text-gray-500 ml-1.5">
                            / {type.total} days
                          </span>
                        </div>
                      </div>

                      <div>
                        {/* Progress bar */}
                        <div className="w-full bg-white/80 rounded-full h-2 mb-2 overflow-hidden border border-gray-100">
                          <div
                            className={`h-full rounded-full transition-all duration-500 ${type.barColor}`}
                            style={{ width: `${percentUsed}%` }}
                          ></div>
                        </div>
                        <div className="flex items-center justify-between text-[13px] font-semibold text-gray-500">
                          <span>{used} days used</span>
                          <span>{remaining} remaining</span>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </section>

          {/* Leave History Table */}
          <section>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-[18px] font-bold text-gray-900 tracking-wide uppercase">
                Leave Request History
              </h2>
              <span className="text-[14px] font-semibold text-gray-400">
                {leaveData.length} records recorded
              </span>
            </div>

            {loading ? (
              <div className="py-8 text-center text-[14px] text-gray-400">Loading history...</div>
            ) : leaveData.length === 0 ? (
              <div className="py-12 text-center bg-gray-50/50 rounded-2xl border border-gray-100">
                <LuCalendarDays className="w-10 h-10 text-gray-300 mx-auto mb-2" />
                <p className="text-[16px] font-bold text-gray-700">No Leave History</p>
                <p className="text-[14px] text-gray-400 mt-1">You haven&apos;t submitted any leave requests yet.</p>
              </div>
            ) : (
              <div className="overflow-x-auto rounded-2xl border border-gray-200/70 bg-white shadow-sm">
                <table className="w-full text-left text-sm">
                  <thead className="bg-gray-50/70 border-b border-gray-200/70 text-[14px] font-bold text-gray-500 uppercase tracking-wider">
                    <tr>
                      <th className="py-3 px-4">Leave Type</th>
                      <th className="py-3 px-4">Dates</th>
                      <th className="py-3 px-4">Days</th>
                      <th className="py-3 px-4">Reason</th>
                      <th className="py-3 px-4">Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100 text-[14px]">
                    {leaveData.map((leave, idx) => {
                      const days = calculateLeaveDays(leave.startDate, leave.endDate);
                      const status = normalizeStatus(leave.status);

                      return (
                        <tr key={leave.id || idx} className="hover:bg-gray-50/60 transition-colors">
                          <td className="py-3.5 px-4 font-bold text-gray-800">
                            {leave.leaveType || 'Leave'}
                          </td>
                          <td className="py-3.5 px-4 text-gray-600 font-medium whitespace-nowrap">
                            {formatDate(leave.startDate)} – {formatDate(leave.endDate)}
                          </td>
                          <td className="py-3.5 px-4 font-bold text-gray-700 whitespace-nowrap">
                            {days} day(s)
                          </td>
                          <td className="py-3.5 px-4 max-w-xs truncate text-gray-500" title={leave.reason}>
                            {leave.reason || '--'}
                          </td>
                          <td className="py-3.5 px-4 whitespace-nowrap">
                            {status === 'APPROVED' && (
                              <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[13px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">
                                <LuCheckCircle2 className="w-3 h-3" />
                                Approved
                              </span>
                            )}
                            {status === 'PENDING' && (
                              <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[13px] font-bold bg-amber-50 text-amber-700 border border-amber-200">
                                <LuClock className="w-3 h-3" />
                                Pending
                              </span>
                            )}
                            {status === 'REJECTED' && (
                              <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[13px] font-bold bg-rose-50 text-rose-700 border border-rose-200">
                                <LuXCircle className="w-3 h-3" />
                                Rejected
                              </span>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      </main>
    </div>
  );
}
