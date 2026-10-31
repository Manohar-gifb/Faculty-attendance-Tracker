import { useState, useEffect, useMemo, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { leaveAPI, profileAPI } from '../services/api';
import toast from 'react-hot-toast';
import dayjs from 'dayjs';

// React Icons (Lucide)
import {
  LuSearch,
  LuBell,
  LuUser,
  LuCalendarDays,
  LuClock,
  LuCheckCircle2,
  LuXCircle,
  LuAlertCircle,
  LuArrowLeft,
  LuEye,
  LuX,
  LuCheck,
  LuChevronRight,
  LuFileText,
  LuSparkles,
  LuCalendar
} from 'react-icons/lu';

// Constant standard leave quotas for calculating total balance
const TOTAL_LEAVE_QUOTA = 37; // 12 CL + 10 SL + 15 EL

const LEAVE_TYPES = [
  { value: 'CASUAL', label: 'Casual Leave (CL)' },
  { value: 'SICK', label: 'Sick Leave (SL)' },
  { value: 'EARNED', label: 'Earned Leave (EL)' },
  { value: 'MEDICAL', label: 'Medical Leave (ML)' },
  { value: 'OD', label: 'On Duty / Permission (OD)' },
  { value: 'MATERNITY', label: 'Maternity Leave' },
  { value: 'PATERNITY', label: 'Paternity Leave' },
];

const HOLIDAYS_DATA = [
  { name: 'New Year Day', date: '2026-01-01', day: 'Thursday', type: 'Gazetted' },
  { name: 'Pongal / Sankranti', date: '2026-01-14', day: 'Wednesday', type: 'Festival' },
  { name: 'Republic Day', date: '2026-01-26', day: 'Monday', type: 'National' },
  { name: 'Maha Shivaratri', date: '2026-02-15', day: 'Sunday', type: 'Gazetted' },
  { name: 'Holi', date: '2026-03-04', day: 'Wednesday', type: 'Festival' },
  { name: 'Ugadi / Telugu New Year', date: '2026-03-20', day: 'Friday', type: 'Regional' },
  { name: 'Good Friday', date: '2026-04-03', day: 'Friday', type: 'Gazetted' },
  { name: 'Dr. B.R. Ambedkar Jayanti', date: '2026-04-14', day: 'Tuesday', type: 'Gazetted' },
  { name: 'Eid ul-Fitr', date: '2026-03-21', day: 'Saturday', type: 'Festival' },
  { name: 'Independence Day', date: '2026-08-15', day: 'Saturday', type: 'National' },
  { name: 'Vinayaka Chavithi', date: '2026-09-14', day: 'Monday', type: 'Festival' },
  { name: 'Mahatma Gandhi Jayanti', date: '2026-10-02', day: 'Friday', type: 'National' },
  { name: 'Vijaya Dasami / Dussehra', date: '2026-10-20', day: 'Tuesday', type: 'Festival' },
  { name: 'Diwali (Deepavali)', date: '2026-11-08', day: 'Sunday', type: 'Festival' },
  { name: 'Christmas', date: '2026-12-25', day: 'Friday', type: 'Festival' },
];

export default function LeaveManagement({ isAdmin = false, defaultTab = 'apply' }) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user } = useAuth();

  // Navigation tab state
  const tabFromUrl = searchParams.get('tab');
  const [activeTab, setActiveTab] = useState(tabFromUrl || defaultTab);

  // Form Mode: LEAVE vs PERMISSION
  const [formMode, setFormMode] = useState('LEAVE');

  // Search query (in header)
  const [searchQuery, setSearchQuery] = useState('');

  // Leaves data
  const [leaves, setLeaves] = useState([]);
  const [pendingApprovals, setPendingApprovals] = useState([]);
  const [loading, setLoading] = useState(true);

  // User approver info
  const [approverName, setApproverName] = useState('Department HOD / Principal');

  // Selected leave for viewing detail modal
  const [selectedLeave, setSelectedLeave] = useState(null);

  // Status sub-filter (for Status tab)
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Cancel action state
  const [cancelLoadingId, setCancelLoadingId] = useState(null);

  // Admin action state
  const [actionLoadingId, setActionLoadingId] = useState(null);
  const [reviewModalLeave, setReviewModalLeave] = useState(null);
  const [reviewAction, setReviewAction] = useState('APPROVE');
  const [reviewerComments, setReviewerComments] = useState('');

  // Apply Leave Form state
  const [formData, setFormData] = useState({
    leaveType: '',
    startDate: '',
    endDate: '',
    reason: '',
    isTomorrowOnly: false,
    isHalfDay: false,
  });
  const [formErrors, setFormErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);

  // Load user profile to get designated approver
  useEffect(() => {
    let isMounted = true;
    const fetchProfile = async () => {
      try {
        const res = await profileAPI.getProfile();
        if (isMounted && res.data) {
          if (res.data.leaveApproverName) {
            setApproverName(res.data.leaveApproverName);
          } else if (res.data.departmentName) {
            setApproverName(`${res.data.departmentName} HOD`);
          }
        }
      } catch (err) {
        // Fall back gracefully to default approver title
      }
    };
    fetchProfile();
    return () => {
      isMounted = false;
    };
  }, []);

  // Load leaves
  const loadLeaves = useCallback(async () => {
    try {
      setLoading(true);
      const res = await leaveAPI.getMyLeaves();
      setLeaves(Array.isArray(res.data) ? res.data : []);

      if (isAdmin) {
        try {
          const pendingRes = await leaveAPI.getPendingLeaves();
          setPendingApprovals(Array.isArray(pendingRes.data) ? pendingRes.data : []);
        } catch {
          setPendingApprovals([]);
        }
      }
    } catch {
      toast.error('Unable to load leave records');
    } finally {
      setLoading(false);
    }
  }, [isAdmin]);

  useEffect(() => {
    loadLeaves();
  }, [loadLeaves]);

  // Handle URL tab changes
  useEffect(() => {
    if (tabFromUrl) {
      setActiveTab(tabFromUrl);
    }
  }, [tabFromUrl]);

  // Calculate duration in days
  const calculateDays = (start, end, isHalf = false) => {
    if (isHalf) return 0.5;
    if (!start || !end) return 0;
    const s = dayjs(start);
    const e = dayjs(end);
    if (!s.isValid() || !e.isValid() || e.isBefore(s)) return 0;
    return e.diff(s, 'day') + 1;
  };

  // Form calculated duration
  const currentDuration = useMemo(() => {
    return calculateDays(formData.startDate, formData.endDate, formData.isHalfDay);
  }, [formData.startDate, formData.endDate, formData.isHalfDay]);

  // Computed summary metrics
  const summaryMetrics = useMemo(() => {
    let approvedCount = 0;
    let approvedDays = 0;
    let pendingCount = 0;
    let rejectedCount = 0;

    leaves.forEach((l) => {
      const status = String(l.status || '').toUpperCase();
      const days = Number(l.durationDays) || calculateDays(l.startDate, l.endDate);
      if (status === 'APPROVED') {
        approvedCount += 1;
        approvedDays += days;
      } else if (status === 'PENDING') {
        pendingCount += 1;
      } else if (status === 'REJECTED') {
        rejectedCount += 1;
      }
    });

    const remainingBalance = Math.max(TOTAL_LEAVE_QUOTA - approvedDays, 0);

    return {
      remainingBalance,
      approvedCount,
      approvedDays,
      pendingCount,
      rejectedCount,
    };
  }, [leaves]);

  // Form change handlers
  const handleFormChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: value,
    }));
    if (formErrors[name]) {
      setFormErrors((prev) => ({ ...prev, [name]: '' }));
    }
  };

  const handleTomorrowToggle = (e) => {
    const checked = e.target.checked;
    const tomorrowStr = dayjs().add(1, 'day').format('YYYY-MM-DD');

    setFormData((prev) => ({
      ...prev,
      isTomorrowOnly: checked,
      startDate: checked ? tomorrowStr : prev.startDate,
      endDate: checked ? tomorrowStr : prev.endDate,
    }));
  };

  const handleHalfDayToggle = (e) => {
    const checked = e.target.checked;
    setFormData((prev) => ({
      ...prev,
      isHalfDay: checked,
    }));
  };

  const handleModeSwitch = (mode) => {
    setFormMode(mode);
    if (mode === 'PERMISSION') {
      const today = dayjs().format('YYYY-MM-DD');
      setFormData((prev) => ({
        ...prev,
        leaveType: 'OD',
        startDate: prev.startDate || today,
        endDate: prev.startDate || today,
        reason: prev.reason || 'Permission for official duty / academic activity',
      }));
    } else {
      setFormData((prev) => ({
        ...prev,
        leaveType: '',
        reason: '',
      }));
    }
  };

  const handleResetForm = () => {
    setFormData({
      leaveType: '',
      startDate: '',
      endDate: '',
      reason: '',
      isTomorrowOnly: false,
      isHalfDay: false,
    });
    setFormErrors({});
  };

  // Form submission
  const handleSubmitLeave = async (e) => {
    e.preventDefault();
    const errors = {};

    if (!formData.leaveType) {
      errors.leaveType = 'Please select a leave type.';
    }
    if (!formData.startDate) {
      errors.startDate = 'Please select a start date.';
    }
    if (!formData.endDate) {
      errors.endDate = 'Please select an end date.';
    } else if (dayjs(formData.endDate).isBefore(dayjs(formData.startDate))) {
      errors.endDate = 'End date cannot be earlier than start date.';
    }
    if (!formData.reason || formData.reason.trim().length < 5) {
      errors.reason = 'Please enter a valid reason (minimum 5 characters).';
    }

    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      toast.error('Please resolve the required fields.');
      return;
    }

    try {
      setSubmitting(true);
      await leaveAPI.applyLeave({
        leaveType: formData.leaveType,
        startDate: formData.startDate,
        endDate: formData.endDate,
        reason: formData.reason.trim(),
      });

      toast.success(
        formMode === 'PERMISSION'
          ? 'Permission request submitted successfully!'
          : 'Leave request submitted successfully!'
      );
      handleResetForm();
      await loadLeaves();
      setActiveTab('requests');
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to submit leave request.';
      toast.error(msg);
    } finally {
      setSubmitting(false);
    }
  };

  // Cancel leave request
  const handleCancelRequest = async (id) => {
    if (!window.confirm('Are you sure you want to cancel this pending leave request?')) {
      return;
    }
    try {
      setCancelLoadingId(id);
      await leaveAPI.cancelLeave(id);
      toast.success('Leave request cancelled successfully');
      await loadLeaves();
      if (selectedLeave?.id === id) {
        setSelectedLeave(null);
      }
    } catch (err) {
      toast.error(err.response?.data?.message || 'Could not cancel leave request');
    } finally {
      setCancelLoadingId(null);
    }
  };

  // Admin Approve / Reject actions
  const handleOpenReviewModal = (leave, action) => {
    setReviewModalLeave(leave);
    setReviewAction(action);
    setReviewerComments('');
  };

  const handleConfirmReview = async () => {
    if (!reviewModalLeave) return;
    try {
      setActionLoadingId(reviewModalLeave.id);
      if (reviewAction === 'APPROVE') {
        await leaveAPI.approveLeave(reviewModalLeave.id, reviewerComments);
        toast.success(`Leave #${reviewModalLeave.id} approved successfully`);
      } else {
        await leaveAPI.rejectLeave(reviewModalLeave.id, reviewerComments);
        toast.success(`Leave #${reviewModalLeave.id} rejected`);
      }
      setReviewModalLeave(null);
      await loadLeaves();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Action failed. Please try again.');
    } finally {
      setActionLoadingId(null);
    }
  };

  // Filter leaves based on activeTab, statusFilter, and search query
  const displayedLeaves = useMemo(() => {
    let list = leaves;

    // In status tab, apply statusFilter
    if (activeTab === 'status' && statusFilter !== 'ALL') {
      list = list.filter((l) => String(l.status).toUpperCase() === statusFilter);
    }

    // In search query filtering
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase().trim();
      list = list.filter(
        (l) =>
          String(l.id).includes(q) ||
          String(l.leaveType).toLowerCase().includes(q) ||
          String(l.reason).toLowerCase().includes(q) ||
          String(l.status).toLowerCase().includes(q)
      );
    }

    return list;
  }, [leaves, activeTab, statusFilter, searchQuery]);

  // Navigation handlers
  const handleTabClick = (tabKey) => {
    if (tabKey === 'balance') {
      navigate(isAdmin ? '/admin/leave-balance' : '/leave-balance');
      return;
    }
    setActiveTab(tabKey);
  };

  // Back to dashboard
  const handleBackToDashboard = () => {
    navigate(isAdmin ? '/admin' : '/dashboard');
  };

  return (
    <div className="bg-[#f0f4f8] min-h-screen flex items-center justify-center p-4 sm:p-8 text-gray-800 antialiased selection:bg-teal-200 selection:text-teal-900">
      {/* Main White Application Container */}
      <main className="w-full max-w-5xl bg-white rounded-[2rem] shadow-[0_20px_50px_-12px_rgba(0,0,0,0.06)] overflow-hidden border border-white/40 backdrop-blur-xl">
        <div className="p-6 sm:p-12">
          {/* Header Section */}
          <header className="flex flex-col md:flex-row justify-between items-start md:items-center mb-8 gap-6">
            <div className="flex items-center gap-4">
              <button
                type="button"
                onClick={handleBackToDashboard}
                title="Back to Dashboard"
                className="p-2.5 rounded-full hover:bg-gray-100 text-gray-500 hover:text-gray-800 transition-colors duration-200 border border-gray-200/60 shadow-sm"
              >
                <LuArrowLeft className="w-5 h-5" />
              </button>
              <div>
                <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-gray-900">
                  {isAdmin ? 'Leave Approvals' : 'Leave Management'}
                </h1>
                <p className="text-xs sm:text-sm text-gray-400 font-medium">
                  Faculty Sync Tracker — Attendance &amp; Leave Portal
                </p>
              </div>
            </div>

            {/* Header Actions */}
            <div className="flex items-center gap-6 w-full md:w-auto justify-between md:justify-end">
              {/* Search Bar */}
              <div className="relative group hidden sm:block">
                <LuSearch className="absolute left-4 top-1/2 -translate-y-1/2 text-gray-400 group-focus-within:text-teal-500 transition-colors text-base" />
                <input
                  type="text"
                  placeholder="Search requests..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="bg-gray-50/80 border border-gray-200/60 rounded-full pl-11 pr-5 py-2 text-sm w-56 lg:w-64 focus:outline-none focus:ring-2 focus:ring-teal-500/20 focus:border-teal-400 focus:bg-white transition-all shadow-sm"
                />
              </div>

              <div className="flex items-center gap-4 border-l border-gray-100 pl-4 sm:pl-6">
                {/* User Profile */}
                <div
                  onClick={() => navigate(isAdmin ? '/admin/profile' : '/profile')}
                  className="flex items-center gap-3 cursor-pointer hover:bg-gray-50 py-1.5 px-3 rounded-full transition-colors duration-200 border border-transparent hover:border-gray-200"
                  title="View Profile"
                >
                  <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-teal-500 to-cyan-400 flex items-center justify-center text-white text-xs font-bold shadow-sm">
                    {user?.fullName?.charAt(0) || <LuUser className="w-4 h-4" />}
                  </div>
                  <span className="text-sm font-semibold text-gray-700">
                    {user?.firstName || user?.fullName?.split(' ')[0] || 'Faculty'}
                  </span>
                </div>

                {/* Notification Bell */}
                <button
                  type="button"
                  onClick={() => setActiveTab(isAdmin ? 'approvals' : 'requests')}
                  className="relative p-2.5 rounded-full hover:bg-gray-100 text-gray-500 hover:text-gray-800 transition-colors duration-200"
                  title="Pending Requests"
                >
                  <LuBell className="text-xl" />
                  {summaryMetrics.pendingCount > 0 && (
                    <span className="absolute top-2 right-2 w-2.5 h-2.5 bg-red-500 rounded-full border-2 border-white animate-pulse"></span>
                  )}
                </button>
              </div>
            </div>
          </header>

          {/* Leave Summary Cards (Section 4) */}
          <section className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
            {/* 1. Total Leave Balance */}
            <div
              onClick={() => navigate(isAdmin ? '/admin/leave-balance' : '/leave-balance')}
              className="bg-gradient-to-br from-blue-50/90 to-blue-100/30 border border-blue-200/60 rounded-2xl p-4 sm:p-5 shadow-sm hover:shadow-md transition-all cursor-pointer group relative overflow-hidden"
            >
              <div className="flex items-center justify-between mb-2">
                <span className="text-[13px] font-bold tracking-wider text-blue-600 uppercase">
                  Total Balance
                </span>
                <span className="p-2 rounded-xl bg-blue-500/10 text-blue-600 group-hover:scale-110 transition-transform">
                  <LuCalendarDays className="w-4 h-4" />
                </span>
              </div>
              <div className="text-2xl sm:text-3xl font-extrabold text-blue-950 tracking-tight">
                {summaryMetrics.remainingBalance}
                <span className="text-sm font-medium text-blue-600 ml-1">Days</span>
              </div>
              <div className="flex items-center justify-between mt-2 text-[13.5px] font-medium text-blue-500">
                <span>View Full Balance</span>
                <LuChevronRight className="w-3.5 h-3.5 group-hover:translate-x-1 transition-transform" />
              </div>
            </div>

            {/* 2. Leaves Approved */}
            <div className="bg-gradient-to-br from-emerald-50/90 to-emerald-100/30 border border-emerald-200/60 rounded-2xl p-4 sm:p-5 shadow-sm">
              <div className="flex items-center justify-between mb-2">
                <span className="text-[13px] font-bold tracking-wider text-emerald-600 uppercase">
                  Approved
                </span>
                <span className="p-2 rounded-xl bg-emerald-500/10 text-emerald-600">
                  <LuCheckCircle2 className="w-4 h-4" />
                </span>
              </div>
              <div className="text-2xl sm:text-3xl font-extrabold text-emerald-950 tracking-tight">
                {summaryMetrics.approvedCount}
                <span className="text-sm font-medium text-emerald-600 ml-1">Reqs</span>
              </div>
              <p className="mt-2 text-[13.5px] font-medium text-emerald-600">
                {summaryMetrics.approvedDays} day(s) taken
              </p>
            </div>

            {/* 3. Pending Requests */}
            <div className="bg-gradient-to-br from-amber-50/90 to-amber-100/30 border border-amber-200/60 rounded-2xl p-4 sm:p-5 shadow-sm">
              <div className="flex items-center justify-between mb-2">
                <span className="text-[13px] font-bold tracking-wider text-amber-600 uppercase">
                  Pending
                </span>
                <span className="p-2 rounded-xl bg-amber-500/10 text-amber-600">
                  <LuClock className="w-4 h-4" />
                </span>
              </div>
              <div className="text-2xl sm:text-3xl font-extrabold text-amber-950 tracking-tight">
                {summaryMetrics.pendingCount}
                <span className="text-sm font-medium text-amber-600 ml-1">Reqs</span>
              </div>
              <p className="mt-2 text-[13.5px] font-medium text-amber-600">
                Awaiting HOD / Review
              </p>
            </div>

            {/* 4. Leaves Rejected */}
            <div className="bg-gradient-to-br from-rose-50/90 to-rose-100/30 border border-rose-200/60 rounded-2xl p-4 sm:p-5 shadow-sm">
              <div className="flex items-center justify-between mb-2">
                <span className="text-[13px] font-bold tracking-wider text-rose-600 uppercase">
                  Rejected
                </span>
                <span className="p-2 rounded-xl bg-rose-500/10 text-rose-600">
                  <LuXCircle className="w-4 h-4" />
                </span>
              </div>
              <div className="text-2xl sm:text-3xl font-extrabold text-rose-950 tracking-tight">
                {summaryMetrics.rejectedCount}
                <span className="text-sm font-medium text-rose-600 ml-1">Reqs</span>
              </div>
              <p className="mt-2 text-[13.5px] font-medium text-rose-600">
                Denied / Withdrawn
              </p>
            </div>
          </section>

          {/* Navigation Links (Section 2) */}
          <nav className="flex items-center gap-6 sm:gap-10 mb-8 overflow-x-auto no-scrollbar border-b border-gray-100 pb-2">
            <button
              type="button"
              onClick={() => handleTabClick('apply')}
              className={`text-sm font-bold pb-2 relative top-[9px] whitespace-nowrap transition-all ${
                activeTab === 'apply'
                  ? 'text-gray-900 border-b-2 border-gray-900'
                  : 'text-gray-400 hover:text-gray-700'
              }`}
            >
              Apply Leave
            </button>

            <button
              type="button"
              onClick={() => handleTabClick('requests')}
              className={`text-[15px] pb-2 relative top-[9px] whitespace-nowrap transition-all ${
                activeTab === 'requests'
                  ? 'font-bold text-gray-900 border-b-2 border-gray-900'
                  : 'font-semibold text-gray-400 hover:text-gray-700'
              }`}
            >
              My Requests
              <span className="ml-1.5 px-2 py-0.5 rounded-full text-[13px] bg-gray-100 text-gray-600">
                {leaves.length}
              </span>
            </button>

            <button
              type="button"
              onClick={() => handleTabClick('status')}
              className={`text-[15px] pb-2 relative top-[9px] whitespace-nowrap transition-all ${
                activeTab === 'status'
                  ? 'font-bold text-gray-900 border-b-2 border-gray-900'
                  : 'font-semibold text-gray-400 hover:text-gray-700'
              }`}
            >
              Status
            </button>

            <button
              type="button"
              onClick={() => handleTabClick('balance')}
              className="text-[15px] font-semibold text-gray-400 hover:text-teal-600 transition-colors whitespace-nowrap pb-2 relative top-[9px] flex items-center gap-1"
            >
              Leave Balance
              <LuChevronRight className="w-3.5 h-3.5 opacity-60" />
            </button>

            <button
              type="button"
              onClick={() => handleTabClick('holidays')}
              className={`text-[15px] pb-2 relative top-[9px] whitespace-nowrap transition-all ${
                activeTab === 'holidays'
                  ? 'font-bold text-gray-900 border-b-2 border-gray-900'
                  : 'font-semibold text-gray-400 hover:text-gray-700'
              }`}
            >
              Holidays
            </button>

            {isAdmin && (
              <button
                type="button"
                onClick={() => handleTabClick('approvals')}
                className={`text-[15px] pb-2 relative top-[9px] whitespace-nowrap transition-all ${
                  activeTab === 'approvals'
                    ? 'font-bold text-gray-900 border-b-2 border-gray-900'
                    : 'font-semibold text-gray-400 hover:text-gray-700'
                }`}
              >
                Approvals
                {pendingApprovals.length > 0 && (
                  <span className="ml-1.5 px-2 py-0.5 rounded-full text-[13px] bg-amber-100 text-amber-700 font-bold">
                    {pendingApprovals.length}
                  </span>
                )}
              </button>
            )}
          </nav>

          {/* ========================================================= */}
          {/* TAB 1: APPLY LEAVE FORM                                    */}
          {/* ========================================================= */}
          {activeTab === 'apply' && (
            <div className="bg-[#fafbfc] rounded-[24px] border border-gray-100/80 p-6 sm:p-10 shadow-sm relative transition-all">
              {/* Toggle Pill */}
              <div className="inline-flex bg-gray-200/60 rounded-xl p-1 mb-8 shadow-inner">
                <button
                  type="button"
                  onClick={() => handleModeSwitch('LEAVE')}
                  className={`px-6 py-2 rounded-lg text-[13.5px] font-bold tracking-wide transition-all ${
                    formMode === 'LEAVE'
                      ? 'bg-[#48c6b6] text-white shadow-sm'
                      : 'text-gray-500 hover:text-gray-800'
                  }`}
                >
                  LEAVE
                </button>
                <button
                  type="button"
                  onClick={() => handleModeSwitch('PERMISSION')}
                  className={`px-6 py-2 rounded-lg text-[13.5px] font-bold tracking-wide transition-all ${
                    formMode === 'PERMISSION'
                      ? 'bg-[#48c6b6] text-white shadow-sm'
                      : 'text-gray-500 hover:text-gray-800'
                  }`}
                >
                  PERMISSION
                </button>
              </div>

              {/* Form Heading */}
              <div className="flex items-center justify-between mb-8">
                <h2 className="text-[20px] font-bold text-[#1a1f2c] tracking-wide uppercase flex items-center gap-2">
                  <LuSparkles className="text-teal-500 w-5 h-5" />
                  {formMode === 'PERMISSION' ? 'Apply Permission / On Duty' : 'Apply Leave'}
                </h2>
                {currentDuration > 0 && (
                  <span className="px-3.5 py-1 rounded-full text-[13.5px] font-bold bg-teal-50 text-teal-700 border border-teal-200/80">
                    Duration: {currentDuration} {currentDuration === 1 ? 'day' : 'days'}
                  </span>
                )}
              </div>

              <form onSubmit={handleSubmitLeave}>
                {/* Input Grid: Leave Type & Approver */}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-x-12 gap-y-6 mb-6">
                  {/* Leave Type Dropdown */}
                  <div>
                    <label className="block text-[14.5px] font-semibold text-gray-600 mb-2.5">
                      Leave Type <span className="text-rose-500">*</span>
                    </label>
                    <div className="relative group">
                      <select
                        name="leaveType"
                        value={formData.leaveType}
                        onChange={handleFormChange}
                        disabled={formMode === 'PERMISSION'}
                        className={`w-full appearance-none bg-white border ${
                          formErrors.leaveType ? 'border-rose-400' : 'border-gray-200'
                        } text-gray-700 text-[15px] py-3.5 px-5 rounded-2xl focus:outline-none focus:border-teal-400 focus:ring-4 focus:ring-teal-400/10 transition-all cursor-pointer shadow-sm hover:border-gray-300`}
                      >
                        <option value="" disabled>
                          Select Leave Type
                        </option>
                        {LEAVE_TYPES.map((t) => (
                          <option key={t.value} value={t.value}>
                            {t.label}
                          </option>
                        ))}
                      </select>
                      <div className="absolute right-5 top-1/2 -translate-y-1/2 pointer-events-none text-gray-400 group-hover:text-gray-600 transition-colors">
                        <LuChevronRight className="rotate-90 w-4 h-4" />
                      </div>
                    </div>
                    {formErrors.leaveType && (
                      <p className="text-rose-500 text-[13.5px] mt-1.5 pl-1">{formErrors.leaveType}</p>
                    )}
                  </div>

                  {/* Approver Text Field */}
                  <div>
                    <label className="block text-[14.5px] font-semibold text-gray-600 mb-2.5">
                      Approver
                    </label>
                    <input
                      type="text"
                      value={approverName}
                      readOnly
                      className="w-full bg-white border border-gray-200 text-gray-800 text-[15px] font-medium py-3.5 px-5 rounded-2xl focus:outline-none focus:border-teal-400 focus:ring-4 focus:ring-teal-400/10 transition-all shadow-sm cursor-not-allowed"
                    />
                    <p className="text-[13px] text-gray-400 mt-1 pl-1">
                      Assigned by administration / department head
                    </p>
                  </div>
                </div>

                {/* Checkboxes Row */}
                <div className="flex items-center gap-10 mb-8 pl-1">
                  <label className="flex items-center gap-3 cursor-pointer group">
                    <input
                      type="checkbox"
                      checked={formData.isTomorrowOnly}
                      onChange={handleTomorrowToggle}
                      className="custom-checkbox"
                    />
                    <span className="text-sm font-semibold text-gray-700 group-hover:text-gray-900 transition-colors select-none">
                      Only For Tomorrow
                    </span>
                  </label>

                  <label className="flex items-center gap-3 cursor-pointer group">
                    <input
                      type="checkbox"
                      checked={formData.isHalfDay}
                      onChange={handleHalfDayToggle}
                      className="custom-checkbox"
                    />
                    <span className="text-sm font-semibold text-gray-700 group-hover:text-gray-900 transition-colors select-none">
                      Half Day
                    </span>
                  </label>
                </div>

                {/* Date Pickers Row */}
                <div className="flex flex-col sm:flex-row items-center gap-4 sm:gap-6 mb-8">
                  {/* Date From */}
                  <div className="relative w-full sm:flex-1 group">
                    <input
                      type="date"
                      name="startDate"
                      value={formData.startDate}
                      min={dayjs().format('YYYY-MM-DD')}
                      disabled={formData.isTomorrowOnly}
                      onChange={handleFormChange}
                      className={`w-full bg-white border ${
                        formErrors.startDate ? 'border-rose-400' : 'border-gray-200'
                      } text-gray-900 text-sm font-semibold py-3.5 px-5 rounded-2xl focus:outline-none focus:border-teal-400 focus:ring-4 focus:ring-teal-400/10 transition-all shadow-sm cursor-pointer hover:border-gray-300 disabled:bg-gray-100 disabled:cursor-not-allowed`}
                    />
                    {formErrors.startDate && (
                      <p className="text-rose-500 text-xs mt-1.5 pl-1">{formErrors.startDate}</p>
                    )}
                  </div>

                  <span className="text-sm font-bold text-gray-400 px-2 uppercase tracking-wider">
                    To
                  </span>

                  {/* Date To */}
                  <div className="relative w-full sm:flex-1 group">
                    <input
                      type="date"
                      name="endDate"
                      value={formData.endDate}
                      min={formData.startDate || dayjs().format('YYYY-MM-DD')}
                      disabled={formData.isTomorrowOnly}
                      onChange={handleFormChange}
                      className={`w-full bg-white border ${
                        formErrors.endDate ? 'border-rose-400' : 'border-gray-200'
                      } text-gray-900 text-sm font-semibold py-3.5 px-5 rounded-2xl focus:outline-none focus:border-teal-400 focus:ring-4 focus:ring-teal-400/10 transition-all shadow-sm cursor-pointer hover:border-gray-300 disabled:bg-gray-100 disabled:cursor-not-allowed`}
                    />
                    {formErrors.endDate && (
                      <p className="text-rose-500 text-xs mt-1.5 pl-1">{formErrors.endDate}</p>
                    )}
                  </div>
                </div>

                {/* Reason Text Area */}
                <div className="mb-10">
                  <label className="block text-[14px] font-bold text-[#1a1f2c] mb-3">
                    Reason for Leave <span className="text-rose-500">*</span>
                  </label>
                  <textarea
                    name="reason"
                    value={formData.reason}
                    onChange={handleFormChange}
                    placeholder="Enter your reason here..."
                    rows={4}
                    className={`w-full bg-white border ${
                      formErrors.reason ? 'border-rose-400' : 'border-gray-200'
                    } text-gray-700 text-sm py-4 px-5 rounded-2xl resize-none focus:outline-none focus:border-teal-400 focus:ring-4 focus:ring-teal-400/10 transition-all shadow-sm hover:border-gray-300`}
                  ></textarea>
                  {formErrors.reason && (
                    <p className="text-rose-500 text-[13.5px] mt-1.5 pl-1">{formErrors.reason}</p>
                  )}
                </div>

                {/* Action Buttons */}
                <div className="flex justify-end items-center gap-4">
                  <button
                    type="button"
                    onClick={handleResetForm}
                    disabled={submitting}
                    className="px-8 py-3 rounded-xl bg-gray-200/80 text-gray-600 font-bold text-[15px] hover:bg-gray-300 hover:text-gray-800 transition-all focus:outline-none focus:ring-2 focus:ring-gray-300 disabled:opacity-50"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={submitting}
                    className="px-10 py-3 rounded-xl bg-[#48c6b6] text-white font-bold text-[15px] hover:bg-[#3baea0] transition-all shadow-[0_8px_16px_-4px_rgba(72,198,182,0.4)] hover:shadow-[0_10px_20px_-4px_rgba(72,198,182,0.5)] hover:-translate-y-0.5 focus:outline-none focus:ring-2 focus:ring-teal-500 focus:ring-offset-2 disabled:opacity-60 disabled:cursor-not-allowed flex items-center gap-2"
                  >
                    {submitting ? (
                      <>
                        <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin"></div>
                        Submitting...
                      </>
                    ) : (
                      'Apply'
                    )}
                  </button>
                </div>
              </form>
            </div>
          )}

          {/* ========================================================= */}
          {/* TAB 2: MY REQUESTS & TAB 3: STATUS                         */}
          {/* ========================================================= */}
          {(activeTab === 'requests' || activeTab === 'status') && (
            <div className="bg-[#fafbfc] rounded-[24px] border border-gray-100/80 p-6 sm:p-8 shadow-sm">
              <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 mb-6">
                <div>
                  <h2 className="text-[20px] font-bold text-gray-900 tracking-wide uppercase">
                    {activeTab === 'status' ? 'Leave Request Status' : 'My Leave Requests'}
                  </h2>
                  <p className="text-[14px] text-gray-500 mt-0.5">
                    Real-time status tracking and review history
                  </p>
                </div>

                {/* Status Quick Filter Buttons (shown on Status tab or general filter) */}
                <div className="flex items-center gap-1.5 bg-gray-200/60 p-1 rounded-xl text-[14px] font-semibold">
                  {['ALL', 'PENDING', 'APPROVED', 'REJECTED'].map((st) => (
                    <button
                      key={st}
                      type="button"
                      onClick={() => setStatusFilter(st)}
                      className={`px-3 py-1.5 rounded-lg transition-all ${
                        statusFilter === st
                          ? 'bg-white text-gray-900 shadow-sm font-bold'
                          : 'text-gray-500 hover:text-gray-800'
                      }`}
                    >
                      {st.charAt(0) + st.slice(1).toLowerCase()}
                    </button>
                  ))}
                </div>
              </div>

              {/* Table / List */}
              {loading ? (
                <div className="py-16 text-center">
                  <div className="w-10 h-10 border-4 border-teal-500 border-t-transparent rounded-full animate-spin mx-auto mb-3"></div>
                  <p className="text-sm font-medium text-gray-500">Loading your leave requests...</p>
                </div>
              ) : displayedLeaves.length === 0 ? (
                <div className="py-16 text-center bg-white rounded-2xl border border-gray-100">
                  <LuCalendarDays className="w-12 h-12 text-gray-300 mx-auto mb-3" />
                  <h3 className="text-[18px] font-bold text-gray-800 mb-1">No Leave Requests Found</h3>
                  <p className="text-[14px] text-gray-400 mb-4 max-w-sm mx-auto">
                    {searchQuery
                      ? 'No records match your search filter.'
                      : "You haven't submitted any leave requests in this category yet."}
                  </p>
                  <button
                    type="button"
                    onClick={() => setActiveTab('apply')}
                    className="px-6 py-2.5 rounded-xl bg-[#48c6b6] text-white font-bold text-[14.5px] hover:bg-[#3baea0] transition-all shadow-sm"
                  >
                    Apply for Leave Now
                  </button>
                </div>
              ) : (
                <div className="overflow-x-auto rounded-2xl border border-gray-200/70 bg-white shadow-sm">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-gray-50/70 border-b border-gray-200/70 text-[14px] font-bold text-gray-500 uppercase tracking-wider">
                      <tr>
                        <th className="py-3.5 px-4">Req #</th>
                        <th className="py-3.5 px-4">Leave Type</th>
                        <th className="py-3.5 px-4">Duration</th>
                        <th className="py-3.5 px-4">Reason</th>
                        <th className="py-3.5 px-4">Applied Date</th>
                        <th className="py-3.5 px-4">Status</th>
                        <th className="py-3.5 px-4 text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {displayedLeaves.map((leave) => {
                        const days =
                          leave.durationDays || calculateDays(leave.startDate, leave.endDate);
                        const status = String(leave.status || 'PENDING').toUpperCase();

                        return (
                          <tr
                            key={leave.id}
                            className="hover:bg-gray-50/60 transition-colors group"
                          >
                            <td className="py-4 px-4 font-mono font-bold text-[13.5px] text-gray-500">
                              #{leave.id}
                            </td>
                            <td className="py-4 px-4 font-semibold text-gray-900 whitespace-nowrap">
                              <span className="inline-block px-2.5 py-1 rounded-md text-[13px] font-bold bg-teal-50 text-teal-700 border border-teal-200/60 mr-2">
                                {leave.leaveType}
                              </span>
                            </td>
                            <td className="py-4 px-4 whitespace-nowrap">
                              <div className="font-semibold text-gray-800 text-[14px]">
                                {dayjs(leave.startDate).format('DD MMM YYYY')} –{' '}
                                {dayjs(leave.endDate).format('DD MMM YYYY')}
                              </div>
                              <span className="text-[13px] font-bold text-gray-400">
                                {days} {days === 1 ? 'day' : 'days'}
                              </span>
                            </td>
                            <td className="py-4 px-4 max-w-xs truncate text-[14px] text-gray-600" title={leave.reason}>
                              {leave.reason}
                            </td>
                            <td className="py-4 px-4 whitespace-nowrap text-[14px] text-gray-500">
                              {leave.createdAt
                                ? dayjs(leave.createdAt).format('DD MMM YYYY')
                                : '--'}
                            </td>
                            <td className="py-4 px-4 whitespace-nowrap">
                              {status === 'APPROVED' && (
                                <span className="inline-flex items-center gap-1 px-3 py-1 rounded-full text-[13px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">
                                  <LuCheckCircle2 className="w-3.5 h-3.5" />
                                  Approved
                                </span>
                              )}
                              {status === 'PENDING' && (
                                <span className="inline-flex items-center gap-1 px-3 py-1 rounded-full text-[13px] font-bold bg-amber-50 text-amber-700 border border-amber-200">
                                  <LuClock className="w-3.5 h-3.5" />
                                  Pending
                                </span>
                              )}
                              {status === 'REJECTED' && (
                                <span className="inline-flex items-center gap-1 px-3 py-1 rounded-full text-[13px] font-bold bg-rose-50 text-rose-700 border border-rose-200">
                                  <LuXCircle className="w-3.5 h-3.5" />
                                  Rejected
                                </span>
                              )}
                              {status === 'CANCELLED' && (
                                <span className="inline-flex items-center gap-1 px-3 py-1 rounded-full text-[13px] font-bold bg-gray-100 text-gray-600 border border-gray-200">
                                  <LuAlertCircle className="w-3.5 h-3.5" />
                                  Cancelled
                                </span>
                              )}
                            </td>
                            <td className="py-4 px-4 text-right whitespace-nowrap">
                              <div className="flex items-center justify-end gap-2">
                                <button
                                  type="button"
                                  onClick={() => setSelectedLeave(leave)}
                                  className="p-1.5 rounded-lg text-gray-500 hover:text-teal-600 hover:bg-teal-50 transition-colors"
                                  title="View Details"
                                >
                                  <LuEye className="w-4 h-4" />
                                </button>
                                {status === 'PENDING' && !isAdmin && (
                                  <button
                                    type="button"
                                    onClick={() => handleCancelRequest(leave.id)}
                                    disabled={cancelLoadingId === leave.id}
                                    className="px-2.5 py-1 rounded-lg text-[13px] font-bold text-rose-600 hover:bg-rose-50 border border-rose-200 transition-colors disabled:opacity-50"
                                    title="Cancel this pending request"
                                  >
                                    {cancelLoadingId === leave.id ? 'Cancelling...' : 'Cancel'}
                                  </button>
                                )}
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {/* ========================================================= */}
          {/* TAB 5: HOLIDAYS                                            */}
          {/* ========================================================= */}
          {activeTab === 'holidays' && (
            <div className="bg-[#fafbfc] rounded-[24px] border border-gray-100/80 p-6 sm:p-8 shadow-sm">
              <div className="flex items-center justify-between mb-6">
                <div>
                  <h2 className="text-[20px] font-bold text-gray-900 tracking-wide uppercase flex items-center gap-2">
                    <LuCalendar className="w-5 h-5 text-teal-500" />
                    College Academic Holiday Calendar
                  </h2>
                  <p className="text-[14px] text-gray-500 mt-0.5">
                    Official gazetted and institutional holidays for the academic year
                  </p>
                </div>
                <span className="text-[13px] font-bold px-3 py-1 bg-teal-50 text-teal-700 border border-teal-200 rounded-full">
                  2026 Academic Schedule
                </span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {HOLIDAYS_DATA.map((h, i) => (
                  <div
                    key={i}
                    className="p-4 rounded-2xl bg-white border border-gray-200/70 shadow-sm flex items-center justify-between hover:border-teal-300 transition-colors"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-12 h-12 rounded-xl bg-teal-50 border border-teal-100 flex flex-col items-center justify-center text-teal-700 font-bold">
                        <span className="text-[12.5px] uppercase">{dayjs(h.date).format('MMM')}</span>
                        <span className="text-base font-extrabold leading-none">
                          {dayjs(h.date).format('DD')}
                        </span>
                      </div>
                      <div>
                        <h4 className="font-bold text-gray-900 text-[15.5px]">{h.name}</h4>
                        <p className="text-[13.5px] text-gray-400 font-medium">
                          {h.day} • {h.type} Holiday
                        </p>
                      </div>
                    </div>
                    <span className="text-[13px] font-semibold px-2.5 py-1 rounded-lg bg-gray-100 text-gray-600">
                      Off Day
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* ========================================================= */}
          {/* TAB: ADMIN APPROVALS (Only for Admin users)               */}
          {/* ========================================================= */}
          {isAdmin && activeTab === 'approvals' && (
            <div className="bg-[#fafbfc] rounded-[24px] border border-gray-100/80 p-6 sm:p-8 shadow-sm">
              <div className="mb-6">
                <h2 className="text-[20px] font-bold text-gray-900 tracking-wide uppercase">
                  Pending Faculty Leave Approvals
                </h2>
                <p className="text-[14px] text-gray-500 mt-0.5">
                  Review, approve, or reject pending leave requests submitted by faculty members
                </p>
              </div>

              {pendingApprovals.length === 0 ? (
                <div className="py-16 text-center bg-white rounded-2xl border border-gray-100">
                  <LuCheckCircle2 className="w-12 h-12 text-emerald-400 mx-auto mb-3" />
                  <h3 className="text-[18px] font-bold text-gray-800 mb-1">All Caught Up!</h3>
                  <p className="text-[14px] text-gray-400">
                    There are no pending faculty leave requests awaiting your approval.
                  </p>
                </div>
              ) : (
                <div className="space-y-4">
                  {pendingApprovals.map((req) => (
                    <div
                      key={req.id}
                      className="p-5 rounded-2xl bg-white border border-gray-200/80 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-4"
                    >
                      <div className="space-y-1.5 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-bold text-[15.5px] text-gray-900">
                            {req.facultyName || 'Faculty Member'}
                          </span>
                          <span className="text-[13.5px] text-gray-400">
                            ({req.employeeId || 'ID'} • {req.departmentName || 'Dept'})
                          </span>
                          <span className="px-2 py-0.5 rounded text-[13px] font-bold bg-teal-50 text-teal-700">
                            {req.leaveType}
                          </span>
                        </div>
                        <div className="text-[14px] font-semibold text-gray-600 flex items-center gap-2">
                          <LuCalendarDays className="w-3.5 h-3.5 text-gray-400" />
                          <span>
                            {dayjs(req.startDate).format('DD MMM YYYY')} to{' '}
                            {dayjs(req.endDate).format('DD MMM YYYY')}
                          </span>
                          <span className="text-gray-400">•</span>
                          <span>{req.durationDays} day(s)</span>
                        </div>
                        <p className="text-[14px] text-gray-600 bg-gray-50 p-2.5 rounded-xl border border-gray-100">
                          <span className="font-bold text-gray-700">Reason:</span> {req.reason}
                        </p>
                      </div>

                      <div className="flex items-center gap-2 self-end md:self-center">
                        <button
                          type="button"
                          onClick={() => handleOpenReviewModal(req, 'APPROVE')}
                          disabled={actionLoadingId === req.id}
                          className="px-4 py-2 rounded-xl bg-emerald-600 text-white font-bold text-[14px] hover:bg-emerald-700 transition-all shadow-sm flex items-center gap-1.5"
                        >
                          <LuCheck className="w-3.5 h-3.5" />
                          Approve
                        </button>
                        <button
                          type="button"
                          onClick={() => handleOpenReviewModal(req, 'REJECT')}
                          disabled={actionLoadingId === req.id}
                          className="px-4 py-2 rounded-xl bg-rose-600 text-white font-bold text-[14px] hover:bg-rose-700 transition-all shadow-sm flex items-center gap-1.5"
                        >
                          <LuX className="w-3.5 h-3.5" />
                          Reject
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </main>

      {/* ========================================================= */}
      {/* MODAL: VIEW LEAVE DETAILS                                 */}
      {/* ========================================================= */}
      {selectedLeave && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm animate-in fade-in">
          <div className="bg-white rounded-3xl p-6 sm:p-8 max-w-lg w-full shadow-2xl border border-gray-100 relative">
            <button
              type="button"
              onClick={() => setSelectedLeave(null)}
              className="absolute top-6 right-6 p-2 rounded-full hover:bg-gray-100 text-gray-400 hover:text-gray-700 transition-colors"
            >
              <LuX className="w-5 h-5" />
            </button>

            <div className="flex items-center gap-3 mb-6">
              <span className="p-3 rounded-2xl bg-teal-50 text-teal-600">
                <LuFileText className="w-6 h-6" />
              </span>
              <div>
                <h3 className="text-xl font-bold text-gray-900">
                  Leave Details #{selectedLeave.id}
                </h3>
                <p className="text-[13.5px] text-gray-400">Comprehensive request audit information</p>
              </div>
            </div>

            <div className="space-y-4 text-sm">
              <div className="grid grid-cols-2 gap-4 p-4 rounded-2xl bg-gray-50 border border-gray-100">
                <div>
                  <span className="text-[13px] font-bold text-gray-400 uppercase">Leave Type</span>
                  <p className="font-bold text-gray-800">{selectedLeave.leaveType}</p>
                </div>
                <div>
                  <span className="text-[13px] font-bold text-gray-400 uppercase">Status</span>
                  <p className="font-bold text-gray-800">{selectedLeave.status}</p>
                </div>
                <div>
                  <span className="text-[13px] font-bold text-gray-400 uppercase">Start Date</span>
                  <p className="font-semibold text-gray-700">
                    {dayjs(selectedLeave.startDate).format('DD MMM YYYY')}
                  </p>
                </div>
                <div>
                  <span className="text-[13px] font-bold text-gray-400 uppercase">End Date</span>
                  <p className="font-semibold text-gray-700">
                    {dayjs(selectedLeave.endDate).format('DD MMM YYYY')}
                  </p>
                </div>
              </div>

              <div>
                <span className="text-[13.5px] font-bold text-gray-500 uppercase">Reason</span>
                <p className="mt-1 p-3.5 rounded-2xl bg-gray-50 border border-gray-100 text-gray-700 text-[14px] leading-relaxed">
                  {selectedLeave.reason}
                </p>
              </div>

              {selectedLeave.reviewerComments && (
                <div>
                  <span className="text-[13.5px] font-bold text-blue-600 uppercase">Reviewer Comments</span>
                  <p className="mt-1 p-3.5 rounded-2xl bg-blue-50 border border-blue-100 text-blue-800 text-[14px]">
                    {selectedLeave.reviewerComments}
                  </p>
                </div>
              )}

              {selectedLeave.reviewedAt && (
                <p className="text-[13px] text-gray-400">
                  Reviewed on: {dayjs(selectedLeave.reviewedAt).format('DD MMM YYYY, hh:mm A')}
                </p>
              )}
            </div>

            <div className="mt-6 flex justify-end gap-3">
              {selectedLeave.status === 'PENDING' && !isAdmin && (
                <button
                  type="button"
                  onClick={() => handleCancelRequest(selectedLeave.id)}
                  disabled={cancelLoadingId === selectedLeave.id}
                  className="px-5 py-2.5 rounded-xl border border-rose-200 text-rose-600 font-bold text-[14.5px] hover:bg-rose-50 transition-all"
                >
                  Cancel Request
                </button>
              )}
              <button
                type="button"
                onClick={() => setSelectedLeave(null)}
                className="px-6 py-2.5 rounded-xl bg-gray-100 text-gray-700 font-bold text-[14.5px] hover:bg-gray-200 transition-all"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================= */}
      {/* MODAL: ADMIN REVIEW ACTION (APPROVE / REJECT)              */}
      {/* ========================================================= */}
      {reviewModalLeave && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm animate-in fade-in">
          <div className="bg-white rounded-3xl p-6 sm:p-8 max-w-md w-full shadow-2xl border border-gray-100">
            <h3 className="text-[20px] font-bold text-gray-900 mb-1">
              {reviewAction === 'APPROVE' ? 'Approve Leave Request' : 'Reject Leave Request'}
            </h3>
            <p className="text-[14px] text-gray-500 mb-4">
              Leave #{reviewModalLeave.id} for {reviewModalLeave.facultyName} ({reviewModalLeave.durationDays} days)
            </p>

            <div className="mb-4">
              <label className="block text-[14px] font-bold text-gray-600 mb-2">
                Reviewer Comments (Optional)
              </label>
              <textarea
                value={reviewerComments}
                onChange={(e) => setReviewerComments(e.target.value)}
                placeholder="Add remarks or notes for the faculty member..."
                rows={3}
                className="w-full bg-gray-50 border border-gray-200 text-gray-800 text-[14.5px] p-3 rounded-xl focus:outline-none focus:border-teal-400"
              ></textarea>
            </div>

            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setReviewModalLeave(null)}
                className="px-5 py-2.5 rounded-xl bg-gray-100 text-gray-600 font-bold text-[14.5px] hover:bg-gray-200 transition-all"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleConfirmReview}
                className={`px-6 py-2.5 rounded-xl text-white font-bold text-[14.5px] transition-all shadow-sm ${
                  reviewAction === 'APPROVE'
                    ? 'bg-emerald-600 hover:bg-emerald-700'
                    : 'bg-rose-600 hover:bg-rose-700'
                }`}
              >
                Confirm {reviewAction === 'APPROVE' ? 'Approval' : 'Rejection'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
