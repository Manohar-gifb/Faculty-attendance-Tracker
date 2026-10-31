import { useEffect, useState } from "react";

import {
    FaCalendarAlt,
    FaCheckCircle,
    FaTimesCircle,
    FaClock,
    FaInfoCircle,
    FaInbox,
    FaUser,
    FaCommentDots,
    FaBuilding,
    FaIdBadge,
    FaPaperPlane,
} from "react-icons/fa";

import dayjs from "dayjs";
import isToday from "dayjs/plugin/isToday";

import { leaveAPI } from "../../services/api";
import { useAuth } from "../../context/AuthContext";


dayjs.extend(isToday);


function InboxTab() {

    const { user } = useAuth();
    const isAdmin = ["HOD", "PRINCIPAL", "VC", "TPO", "DEVELOPER"].includes(user?.role);

    const [pendingLeaves, setPendingLeaves] = useState([]);
    const [myLeaves, setMyLeaves] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [expandedId, setExpandedId] = useState(null);
    const [actionLoading, setActionLoading] = useState(null);
    const [commentText, setCommentText] = useState({});


    // =====================================================
    // LOAD DATA
    // =====================================================

    useEffect(() => {
        loadData();
        // eslint-disable-next-line react-hooks/exhaustive-deps -- mount-only initial load (loadData is re-created each render)
    }, []);

    const loadData = async () => {
        try {
            setLoading(true);
            setError("");

            if (isAdmin) {
                // HOD/Admin: load pending leaves needing their approval
                const pendingRes = await leaveAPI.getPendingForMe();
                setPendingLeaves(Array.isArray(pendingRes.data) ? pendingRes.data : []);
            }

            // Everyone: load own leaves
            const myRes = await leaveAPI.getMyLeaves();
            setMyLeaves(Array.isArray(myRes.data) ? myRes.data : []);

        } catch (err) {
            console.error("Load inbox error:", err);
            setError(err.response?.data?.message || "Unable to load notifications.");
        } finally {
            setLoading(false);
        }
    };


    // =====================================================
    // APPROVE / REJECT
    // =====================================================

    const handleApprove = async (leaveId) => {
        try {
            setActionLoading(leaveId);
            await leaveAPI.approveLeave(leaveId, commentText[leaveId] || "");
            setPendingLeaves((prev) => prev.filter((l) => l.id !== leaveId));
            setCommentText((prev) => { const c = { ...prev }; delete c[leaveId]; return c; });
            setExpandedId(null);
        } catch (err) {
            console.error("Approve error:", err);
            setError(err.response?.data?.message || "Failed to approve leave.");
        } finally {
            setActionLoading(null);
        }
    };

    const handleReject = async (leaveId) => {
        try {
            setActionLoading(leaveId);
            await leaveAPI.rejectLeave(leaveId, commentText[leaveId] || "");
            setPendingLeaves((prev) => prev.filter((l) => l.id !== leaveId));
            setCommentText((prev) => { const c = { ...prev }; delete c[leaveId]; return c; });
            setExpandedId(null);
        } catch (err) {
            console.error("Reject error:", err);
            setError(err.response?.data?.message || "Failed to reject leave.");
        } finally {
            setActionLoading(null);
        }
    };


    // =====================================================
    // STATUS CONFIG
    // =====================================================

    const statusConfig = {
        PENDING:   { icon: FaClock,      color: "#d97706", bg: "#fffbeb", border: "#fde68a", label: "Pending" },
        APPROVED:  { icon: FaCheckCircle, color: "#16a34a", bg: "#f0fdf4", border: "#bbf7d0", label: "Approved" },
        REJECTED:  { icon: FaTimesCircle, color: "#dc2626", bg: "#fef2f2", border: "#fecdd3", label: "Rejected" },
        CANCELLED: { icon: FaTimesCircle, color: "#6b7280", bg: "#f9fafb", border: "#e5e7eb", label: "Cancelled" },
    };

    const leaveTypeLabels = {
        CASUAL: "Casual Leave", MEDICAL: "Medical Leave", OD: "On Duty (OD)",
        EARNED: "Earned Leave", SICK: "Sick Leave", PERSONAL: "Personal Leave",
        MATERNITY: "Maternity Leave", PATERNITY: "Paternity Leave",
    };


    // =====================================================
    // RENDER
    // =====================================================

    return (

        <div className="fd-inbox-tab">

            {/* =================================================
                HEADER
            ================================================= */}

            <div className="fd-inbox-header">
                <FaInbox className="fd-inbox-header-icon" />
                <div>
                    <h1>{isAdmin ? "Leave Approvals" : "My Inbox"}</h1>
                    <p>{isAdmin ? "Review and manage pending leave requests" : "Your leave request notifications"}</p>
                </div>
            </div>


            {/* =================================================
                LOADING
            ================================================= */}

            {loading && (
                <div className="fd-inbox-loading">
                    <div className="fd-inbox-spinner"></div>
                    <span>Loading notifications...</span>
                </div>
            )}


            {/* =================================================
                ERROR
            ================================================= */}

            {error && !loading && (
                <div className="fd-inbox-error">
                    <FaInfoCircle />
                    <span>{error}</span>
                    <button onClick={() => { setError(""); loadData(); }}>Retry</button>
                </div>
            )}


            {/* =================================================
                ADMIN: PENDING LEAVE REQUESTS (APPROVE/REJECT)
            ================================================= */}

            {!loading && isAdmin && (
                <>
                    <div className="fd-inbox-section-header">
                        <h2>
                            <FaClock /> Pending Approvals
                            <span className="fd-inbox-count">{pendingLeaves.length}</span>
                        </h2>
                    </div>

                    {pendingLeaves.length === 0 ? (
                        <div className="fd-inbox-empty-small">
                            <FaCheckCircle className="fd-inbox-empty-icon" />
                            <p>All caught up! No pending leave requests.</p>
                        </div>
                    ) : (
                        <div className="fd-inbox-email-list">
                            {pendingLeaves.map((leave) => {
                                const isExpanded = expandedId === leave.id;
                                const isLoading = actionLoading === leave.id;

                                return (
                                    <div
                                        className={`fd-inbox-email-card fd-inbox-pending-card ${isExpanded ? "fd-inbox-email-expanded" : ""}`}
                                        key={leave.id}
                                    >
                                        {/* TOP ROW */}
                                        <div className="fd-inbox-email-top"
                                            onClick={() => setExpandedId(isExpanded ? null : leave.id)}>

                                            <div className="fd-inbox-email-left">
                                                <div className="fd-inbox-email-avatar fd-inbox-avatar-pending">
                                                    {leave.facultyName ? leave.facultyName.charAt(0).toUpperCase() : <FaUser />}
                                                </div>
                                                <div className="fd-inbox-email-info">
                                                    <div className="fd-inbox-email-from">
                                                        <strong>{leave.facultyName}</strong>
                                                        <span className="fd-inbox-email-type">
                                                            {leaveTypeLabels[leave.leaveType] || leave.leaveType}
                                                        </span>
                                                    </div>
                                                    <div className="fd-inbox-email-subject">
                                                        {leave.reason
                                                            ? leave.reason.substring(0, 60) + (leave.reason.length > 60 ? "..." : "")
                                                            : "No reason provided"
                                                        }
                                                    </div>
                                                </div>
                                            </div>

                                            <div className="fd-inbox-email-right">
                                                <span className="fd-inbox-email-time">
                                                    {dayjs(leave.createdAt).fromNow ? dayjs(leave.createdAt).fromNow() : dayjs(leave.createdAt).format("MMM D")}
                                                </span>
                                            </div>
                                        </div>

                                        {/* EXPANDED CONTENT */}
                                        {isExpanded && (
                                            <div className="fd-inbox-email-details">

                                                {/* Faculty Info */}
                                                <div className="fd-inbox-email-detail-row">
                                                    <FaUser className="fd-inbox-email-detail-icon" />
                                                    <div>
                                                        <span className="fd-inbox-email-detail-label">Faculty</span>
                                                        <span className="fd-inbox-email-detail-value">{leave.facultyName}</span>
                                                    </div>
                                                </div>

                                                <div className="fd-inbox-email-detail-row">
                                                    <FaIdBadge className="fd-inbox-email-detail-icon" />
                                                    <div>
                                                        <span className="fd-inbox-email-detail-label">Employee ID</span>
                                                        <span className="fd-inbox-email-detail-value">{leave.employeeId}</span>
                                                    </div>
                                                </div>

                                                <div className="fd-inbox-email-detail-row">
                                                    <FaBuilding className="fd-inbox-email-detail-icon" />
                                                    <div>
                                                        <span className="fd-inbox-email-detail-label">Department</span>
                                                        <span className="fd-inbox-email-detail-value">{leave.departmentName}</span>
                                                    </div>
                                                </div>

                                                {/* Dates */}
                                                <div className="fd-inbox-email-detail-row">
                                                    <FaCalendarAlt className="fd-inbox-email-detail-icon" />
                                                    <div>
                                                        <span className="fd-inbox-email-detail-label">Leave Period</span>
                                                        <span className="fd-inbox-email-detail-value">
                                                            {dayjs(leave.startDate).format("MMM D, YYYY")}
                                                            {" — "}
                                                            {dayjs(leave.endDate).format("MMM D, YYYY")}
                                                            {" ("}{leave.durationDays || 1}{" day"}{(leave.durationDays || 1) !== 1 ? "s" : ""}{")"}
                                                        </span>
                                                    </div>
                                                </div>

                                                {/* Reason */}
                                                <div className="fd-inbox-email-detail-row">
                                                    <FaCommentDots className="fd-inbox-email-detail-icon" />
                                                    <div>
                                                        <span className="fd-inbox-email-detail-label">Reason</span>
                                                        <span className="fd-inbox-email-detail-value">{leave.reason || "No reason"}</span>
                                                    </div>
                                                </div>

                                                {/* Comment Input */}
                                                <div className="fd-inbox-comment-box">
                                                    <FaCommentDots className="fd-inbox-comment-icon" />
                                                    <input
                                                        type="text"
                                                        placeholder="Add a comment (optional)..."
                                                        value={commentText[leave.id] || ""}
                                                        onChange={(e) => setCommentText((prev) => ({ ...prev, [leave.id]: e.target.value }))}
                                                        disabled={isLoading}
                                                    />
                                                </div>

                                                {/* Approve / Reject Buttons */}
                                                <div className="fd-inbox-action-buttons">
                                                    <button
                                                        className="fd-inbox-btn-approve"
                                                        onClick={() => handleApprove(leave.id)}
                                                        disabled={isLoading}
                                                    >
                                                        {isLoading ? <span className="fd-mini-spinner" /> : <FaCheckCircle />}
                                                        Approve
                                                    </button>
                                                    <button
                                                        className="fd-inbox-btn-reject"
                                                        onClick={() => handleReject(leave.id)}
                                                        disabled={isLoading}
                                                    >
                                                        {isLoading ? <span className="fd-mini-spinner" /> : <FaTimesCircle />}
                                                        Reject
                                                    </button>
                                                </div>

                                                <div className="fd-inbox-email-timestamp">
                                                    Submitted on {dayjs(leave.createdAt).format("MMMM D, YYYY [at] h:mm A")}
                                                </div>
                                            </div>
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </>
            )}


            {/* =================================================
                MY LEAVE REQUESTS (ALL ROLES)
            ================================================= */}

            {!loading && (
                <>
                    <div className="fd-inbox-section-header">
                        <h2>
                            <FaPaperPlane /> {isAdmin ? "My Leave Requests" : "Leave Requests"}
                            <span className="fd-inbox-count">{myLeaves.length}</span>
                        </h2>
                    </div>

                    {myLeaves.length === 0 ? (
                        <div className="fd-inbox-empty-small">
                            <FaInbox className="fd-inbox-empty-icon" />
                            <p>No leave requests yet.</p>
                        </div>
                    ) : (
                        <div className="fd-inbox-email-list">
                            {[...myLeaves].sort((a, b) => dayjs(b.createdAt).diff(dayjs(a.createdAt))).map((leave) => {
                                const config = statusConfig[leave.status] || statusConfig.PENDING;
                                const StatusIcon = config.icon;
                                const isExpanded = expandedId === `my-${leave.id}`;

                                return (
                                    <div
                                        className={`fd-inbox-email-card ${isExpanded ? "fd-inbox-email-expanded" : ""}`}
                                        key={leave.id}
                                        style={{ borderLeftColor: config.color }}
                                    >
                                        <div className="fd-inbox-email-top"
                                            onClick={() => setExpandedId(isExpanded ? null : `my-${leave.id}`)}>

                                            <div className="fd-inbox-email-left">
                                                <div className="fd-inbox-email-avatar"
                                                    style={{ background: config.bg, color: config.color }}>
                                                    <StatusIcon />
                                                </div>
                                                <div className="fd-inbox-email-info">
                                                    <div className="fd-inbox-email-from">
                                                        <span className="fd-inbox-email-type">
                                                            {leaveTypeLabels[leave.leaveType] || leave.leaveType}
                                                        </span>
                                                        <span className="fd-inbox-email-status"
                                                            style={{ background: config.bg, color: config.color, borderColor: config.border }}>
                                                            {config.label}
                                                        </span>
                                                    </div>
                                                    <div className="fd-inbox-email-subject">
                                                        {leave.reason
                                                            ? leave.reason.substring(0, 60) + (leave.reason.length > 60 ? "..." : "")
                                                            : "No reason provided"
                                                        }
                                                    </div>
                                                </div>
                                            </div>

                                            <div className="fd-inbox-email-right">
                                                <span className="fd-inbox-email-time">
                                                    {dayjs(leave.createdAt).format("MMM D")}
                                                </span>
                                            </div>
                                        </div>

                                        {isExpanded && (
                                            <div className="fd-inbox-email-details">
                                                <div className="fd-inbox-email-detail-row">
                                                    <FaCalendarAlt className="fd-inbox-email-detail-icon" />
                                                    <div>
                                                        <span className="fd-inbox-email-detail-label">Leave Period</span>
                                                        <span className="fd-inbox-email-detail-value">
                                                            {dayjs(leave.startDate).format("MMM D, YYYY")}
                                                            {" — "}
                                                            {dayjs(leave.endDate).format("MMM D, YYYY")}
                                                            {" ("}{leave.durationDays || 1}{" day"}{(leave.durationDays || 1) !== 1 ? "s" : ""}{")"}
                                                        </span>
                                                    </div>
                                                </div>

                                                <div className="fd-inbox-email-detail-row">
                                                    <FaCommentDots className="fd-inbox-email-detail-icon" />
                                                    <div>
                                                        <span className="fd-inbox-email-detail-label">Reason</span>
                                                        <span className="fd-inbox-email-detail-value">{leave.reason || "No reason"}</span>
                                                    </div>
                                                </div>

                                                {leave.reviewerComments && (
                                                    <div className="fd-inbox-email-detail-row fd-inbox-email-reviewer">
                                                        <FaCommentDots className="fd-inbox-email-detail-icon" />
                                                        <div>
                                                            <span className="fd-inbox-email-detail-label">Reviewer Comments</span>
                                                            <span className="fd-inbox-email-detail-value">{leave.reviewerComments}</span>
                                                        </div>
                                                    </div>
                                                )}

                                                <div className="fd-inbox-email-timestamp">
                                                    Submitted on {dayjs(leave.createdAt).format("MMMM D, YYYY [at] h:mm A")}
                                                    {leave.reviewedAt && (
                                                        <> • Reviewed {dayjs(leave.reviewedAt).format("MMM D, YYYY")}</>
                                                    )}
                                                </div>
                                            </div>
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </>
            )}

        </div>
    );
}

export default InboxTab;
