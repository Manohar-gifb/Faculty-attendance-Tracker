import "./DeveloperDashboard.css";

import { useEffect, useState, useRef } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import dayjs from "dayjs";

import {
    FaUsers,
    FaUserPlus,
    FaUserShield,
    FaUserGraduate,
    FaSearch,
    FaSignOutAlt,
    FaUserCircle,
    FaBuilding,
    FaEnvelope,
    FaPhoneAlt,
    FaIdBadge,
    FaTrash,
    FaEdit,
    FaTimes,
    FaCheck,
    FaInfoCircle,
    FaKey,
    FaClipboardList,
    FaCamera,
    FaUserCheck,
    FaUserSlash,
    FaHourglassHalf,
    FaHistory,
    FaChevronLeft,
    FaChevronRight,
} from "react-icons/fa";

import { developerAPI, imageSrc } from "../services/api";
import { useAuth } from "../context/AuthContext";
import WebcamCapture from "../components/common/WebcamCapture";


// =========================================================
// ROLES
// =========================================================

const ROLES = [
    { value: "FACULTY", label: "Faculty", color: "#3b82f6", icon: FaUserGraduate },
    { value: "HOD", label: "Head of Department", color: "#8b5cf6", icon: FaUserShield },
    { value: "PRINCIPAL", label: "Principal", color: "#f59e0b", icon: FaUserShield },
    { value: "VC", label: "Vice Chancellor", color: "#ef4444", icon: FaUserShield },
    { value: "TPO", label: "Training & Placement Officer", color: "#10b981", icon: FaUserShield },
];


// =========================================================
// AUDIT ACTION LABELS (from backend audit trail)
// =========================================================

const ACTION_LABELS = {
    ACCOUNT_CREATED: "Account created",
    ACCOUNT_UPDATED: "Account updated",
    ACCOUNT_ACTIVATED: "Account activated",
    ACCOUNT_DEACTIVATED: "Account deactivated",
    ACTIVATION_RESENT: "Activation email resent",
    PASSWORD_RESET_INITIATED: "Password reset link issued",
    APPROVER_UPDATED: "Leave approver updated",
    APPROVER_MAPPING: "Faculty mapped under approver",
    DEPARTMENT_CREATED: "Department created",
    DEPARTMENT_UPDATED: "Department updated",
    DEPARTMENT_DELETED: "Department deleted",
    DEPARTMENT_DEACTIVATED: "Department deactivated",
};


const STATUS_STYLES = {
    ACTIVE: { background: "#d1fae5", color: "#059669" },
    PENDING: { background: "#fef3c7", color: "#d97706" },
    INACTIVE: { background: "#fee2e2", color: "#dc2626" },
};

const STATUS_LABELS = {
    ACTIVE: "Active",
    PENDING: "Pending Activation",
    INACTIVE: "Inactive",
};


// =========================================================
// CREATE ACCOUNT MODAL
// =========================================================

function CreateAccountModal({ onClose, onSuccess, departments, approvers, facultyList = [], editAccount = null }) {

    const isEdit = Boolean(editAccount);

    const [formData, setFormData] = useState(() =>
        isEdit
            ? {
                firstName: editAccount.firstName || "",
                lastName: editAccount.lastName || "",
                email: editAccount.email || "",
                phone: editAccount.phone || "",
                role: editAccount.role || "",
                departmentId: editAccount.departmentId != null ? String(editAccount.departmentId) : "",
                employeeId: editAccount.employeeId || "",
                leaveApproverId: editAccount.leaveApproverId ? String(editAccount.leaveApproverId) : "",
                secondaryApproverId: editAccount.secondaryApproverId ? String(editAccount.secondaryApproverId) : "",
            }
            : {
                firstName: "",
                lastName: "",
                email: "",
                phone: "",
                role: "",
                departmentId: "",
                employeeId: "",
                leaveApproverId: "",
                secondaryApproverId: "",
            }
    );

    const [selectedFacultyIds, setSelectedFacultyIds] = useState([]);
    const [capturedPhoto, setCapturedPhoto] = useState(null);
    const [currentPhotoUrl, setCurrentPhotoUrl] = useState(editAccount?.profilePictureUrl || null);
    const [showPhotoPicker, setShowPhotoPicker] = useState(false);
    const [photoUploading, setPhotoUploading] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");


    // ---- FIELD CHANGE ----
    const handleChange = (e) => {
        const { name, value } = e.target;
        setFormData((prev) => ({ ...prev, [name]: value }));
        setSelectedFacultyIds([]);
        setError("");
        setSuccess("");
    };

    // ---- PHOTO CAPTURED ----
    const handlePhotoCapture = (blob) => {
        setCapturedPhoto(blob);
    };

    const handlePhotoClear = () => {
        setCapturedPhoto(null);
    };

    // ---- SAVE PHOTO ONLY (EDIT MODE) ----
    const handleSavePhotoOnly = async () => {
        if (!capturedPhoto || !isEdit) return;
        try {
            setPhotoUploading(true);
            setError("");
            const res = await developerAPI.uploadAccountPhoto(editAccount.id, capturedPhoto);
            const newUrl = res.data?.account?.profilePictureUrl;
            setCurrentPhotoUrl(newUrl);
            if (editAccount) editAccount.profilePictureUrl = newUrl;
            setCapturedPhoto(null);
            setShowPhotoPicker(false);
            setSuccess(res.data?.message || "Profile photo updated successfully!");
            onSuccess(); // refresh parent account list
            setTimeout(() => setSuccess(""), 4000);
        } catch (err) {
            console.error("Photo upload error:", err);
            setError(err.response?.data?.message || "Failed to update profile photo.");
        } finally {
            setPhotoUploading(false);
        }
    };

    // ---- TOGGLE FACULTY SELECTION ----
    const toggleFaculty = (facultyId) => {
        setSelectedFacultyIds((prev) =>
            prev.includes(facultyId)
                ? prev.filter((id) => id !== facultyId)
                : [...prev, facultyId]
        );
    };

    // ---- SELECT / DESELECT ALL FACULTY IN DEPARTMENT ----
    const toggleAllFaculty = () => {
        const deptFaculty = facultyList
            .filter((f) => f.role === "FACULTY" && String(f.departmentId) === String(formData.departmentId))
            .map((f) => f.id);
        const allSelected = deptFaculty.every((id) => selectedFacultyIds.includes(id));
        if (allSelected) {
            setSelectedFacultyIds((prev) => prev.filter((id) => !deptFaculty.includes(id)));
        } else {
            setSelectedFacultyIds((prev) => [...new Set([...prev, ...deptFaculty])]);
        }
    };

    // ---- FACULTY FILTERED BY DEPARTMENT ----
    const departmentFaculty = facultyList.filter(
        (f) => f.role === "FACULTY" && String(f.departmentId) === String(formData.departmentId)
    );


    // ---- VALIDATE ----
    const validate = () => {
        if (!formData.firstName.trim()) return "First name is required.";
        if (!formData.lastName.trim()) return "Last name is required.";
        if (!formData.email.trim()) return "Email is required.";
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email)) return "Enter a valid email.";
        if (!formData.role) return "Please select a role.";
        if (!formData.departmentId) return "Please select a department.";
        if (!isEdit && formData.role === "FACULTY" && !capturedPhoto) {
            return "Profile photo is required. Please capture a photo using your camera or upload an approved image before creating the account.";
        }
        return "";
    };


    // ---- SUBMIT ----
    const handleSubmit = async (e) => {
        e.preventDefault();
        const validationError = validate();
        if (validationError) {
            setError(validationError);
            return;
        }

        try {
            setLoading(true);
            setError("");

            const payload = {
                firstName: formData.firstName.trim(),
                lastName: formData.lastName.trim(),
                email: formData.email.trim(),
                phone: formData.phone.trim(),
                role: formData.role,
                departmentId: formData.departmentId ? Number(formData.departmentId) : undefined,
                employeeId: isEdit ? undefined : (formData.employeeId.trim() || undefined),
                leaveApproverId: formData.leaveApproverId ? Number(formData.leaveApproverId) : undefined,
                secondaryApproverId: formData.secondaryApproverId ? Number(formData.secondaryApproverId) : undefined,
                mappedFacultyIds: !isEdit && selectedFacultyIds.length > 0 ? selectedFacultyIds : undefined,
            };

            if (isEdit) {
                await developerAPI.updateAccount(editAccount.id, payload);
                if (capturedPhoto) {
                    await developerAPI.uploadAccountPhoto(editAccount.id, capturedPhoto);
                }
                setSuccess("Account updated successfully!");
            } else {
                const res = await developerAPI.createAccount(payload, capturedPhoto);
                setSuccess(res.data?.message || "Account created successfully!");
            }

            setTimeout(() => {
                onSuccess();
                onClose();
            }, 1200);

        } catch (err) {
            console.error("Save account error:", err);
            setError(err.response?.data?.message || (isEdit ? "Failed to update account. Please try again." : "Failed to create account. Please try again."));
        } finally {
            setLoading(false);
        }
    };


    // ---- RENDER ----
    return (
        <div className="dev-modal-overlay" onClick={onClose}>
            <div className="dev-modal" onClick={(e) => e.stopPropagation()}>

                {/* MODAL HEADER */}
                <div className="dev-modal-header">
                    <div className="dev-modal-header-left">
                        {isEdit ? <FaEdit className="dev-modal-header-icon" /> : <FaUserPlus className="dev-modal-header-icon" />}
                        <div>
                            <h2>{isEdit ? "Edit Account" : "Create New Account"}</h2>
                            <p>{isEdit ? "Update account details and assignments" : "Add a new faculty or admin account"}</p>
                        </div>
                    </div>
                    <button className="dev-modal-close" onClick={onClose} type="button">
                        <FaTimes />
                    </button>
                </div>


                {/* MESSAGES */}
                {error && (
                    <div className="dev-alert dev-alert-error">
                        <FaInfoCircle /><span>{error}</span>
                    </div>
                )}
                {success && (
                    <div className="dev-alert dev-alert-success">
                        <FaCheck /><span>{success}</span>
                    </div>
                )}


                {/* FORM */}
                <form className="dev-form" onSubmit={handleSubmit}>

                    {/* NAME ROW */}
                    <div className="dev-form-row">
                        <div className="dev-form-group">
                            <label>First Name *</label>
                            <input
                                type="text"
                                name="firstName"
                                value={formData.firstName}
                                onChange={handleChange}
                                placeholder="Enter first name"
                                disabled={loading}
                            />
                        </div>
                        <div className="dev-form-group">
                            <label>Last Name *</label>
                            <input
                                type="text"
                                name="lastName"
                                value={formData.lastName}
                                onChange={handleChange}
                                placeholder="Enter last name"
                                disabled={loading}
                            />
                        </div>
                    </div>


                    {/* EMAIL */}
                    <div className="dev-form-group">
                        <label>Email *</label>
                        <div className="dev-form-input-icon">
                            <FaEnvelope className="dev-input-icon" />
                            <input
                                type="email"
                                name="email"
                                value={formData.email}
                                onChange={handleChange}
                                placeholder="Enter email address"
                                disabled={loading || isEdit}
                            />
                        </div>
                    </div>


                    {/* PHONE */}
                    <div className="dev-form-group">
                        <label>Phone</label>
                        <div className="dev-form-input-icon">
                            <FaPhoneAlt className="dev-input-icon" />
                            <input
                                type="tel"
                                name="phone"
                                value={formData.phone}
                                onChange={handleChange}
                                placeholder="Enter phone number"
                                disabled={loading}
                            />
                        </div>
                    </div>


                    {/* EMPLOYEE ID */}
                    <div className="dev-form-group">
                        <label>Employee ID</label>
                        <div className="dev-form-input-icon">
                            <FaIdBadge className="dev-input-icon" />
                            <input
                                type="text"
                                name="employeeId"
                                value={formData.employeeId}
                                onChange={handleChange}
                                placeholder="e.g. EMP001"
                                disabled={loading || isEdit}
                            />
                        </div>
                    </div>


                    {/* EMAIL INFO */}
                    <div className="dev-form-info">
                        <FaInfoCircle />
                        <span>
                            {isEdit ? (
                                <>Changes are saved to the database immediately. Email and Employee ID cannot be changed here.</>
                            ) : (
                                <>A setup link will be sent to <strong>{formData.email || "the email above"}</strong>. The new user will click it to create their own password.</>
                            )}
                        </span>
                    </div>

                    {/* ========================================================
                        WEBCAM PHOTO CAPTURE / PHOTO DISPLAY
                    ======================================================== */}
                    {!isEdit ? (
                        <>
                            <div className="dev-form-section-title dev-form-section-faculty">
                                <FaCamera />
                                <span>Profile Photo {formData.role === "FACULTY" ? "(Required for Faculty)" : "(Optional)"}</span>
                            </div>
                            {formData.role === "FACULTY" && !capturedPhoto && (
                                <div className="dev-alert dev-alert-error" style={{ marginBottom: "12px" }}>
                                    <FaInfoCircle />
                                    <span>Profile photo is required. Please capture or upload a photo to create a Faculty account.</span>
                                </div>
                            )}
                            {capturedPhoto && (
                                <div className="dev-alert dev-alert-success" style={{ marginBottom: "12px" }}>
                                    <FaCheck />
                                    <span>Photo ready. Will be registered with the new account.</span>
                                </div>
                            )}
                            <WebcamCapture
                                onCapture={handlePhotoCapture}
                                onClear={handlePhotoClear}
                                disabled={loading}
                                allowUpload={true}
                            />
                        </>
                    ) : (
                        <div style={{
                            padding: "16px",
                            borderRadius: "10px",
                            background: "#f8fafc",
                            border: "1px solid #e2e8f0",
                            marginBottom: "16px"
                        }}>
                            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "16px", flexWrap: "wrap" }}>
                                <div style={{ display: "flex", alignItems: "center", gap: "14px" }}>
                                    <div style={{
                                        width: "64px",
                                        height: "64px",
                                        borderRadius: "50%",
                                        overflow: "hidden",
                                        display: "flex",
                                        alignItems: "center",
                                        justifyContent: "center",
                                        background: "#e2e8f0",
                                        color: "#64748b",
                                        fontSize: "24px",
                                        flexShrink: 0,
                                        border: "2px solid #cbd5e1"
                                    }}>
                                        {currentPhotoUrl ? (
                                            <>
                                                <img
                                                    src={imageSrc(currentPhotoUrl)}
                                                    alt={editAccount?.fullName || "User photo"}
                                                    style={{ width: "100%", height: "100%", objectFit: "cover" }}
                                                    onError={(e) => {
                                                        e.currentTarget.style.display = "none";
                                                        if (e.currentTarget.nextSibling) e.currentTarget.nextSibling.style.display = "inline";
                                                    }}
                                                />
                                                <span style={{ display: "none" }}>
                                                    {editAccount?.fullName ? editAccount.fullName.charAt(0).toUpperCase() : <FaUserCircle />}
                                                </span>
                                            </>
                                        ) : (
                                            <span>{editAccount?.fullName ? editAccount.fullName.charAt(0).toUpperCase() : <FaUserCircle />}</span>
                                        )}
                                    </div>
                                    <div>
                                        <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
                                            <span style={{ fontSize: "0.92rem", fontWeight: "600", color: "#1e293b" }}>
                                                Profile Photo
                                            </span>
                                            {!currentPhotoUrl && (
                                                <span style={{
                                                    background: "#fef3c7",
                                                    color: "#d97706",
                                                    fontSize: "0.72rem",
                                                    fontWeight: "600",
                                                    padding: "2px 8px",
                                                    borderRadius: "12px",
                                                    border: "1px solid #fde68a"
                                                }}>
                                                    Photo Required
                                                </span>
                                            )}
                                        </div>
                                        <div style={{ fontSize: "0.8rem", color: "#64748b", marginTop: "2px" }}>
                                            {currentPhotoUrl
                                                ? "Registered profile photo for face verification and profile display"
                                                : "No profile photo registered. Face verification is blocked until a photo is uploaded."}
                                        </div>
                                    </div>
                                </div>
                                <button
                                    type="button"
                                    className="dev-btn"
                                    style={{
                                        background: "#eff6ff",
                                        color: "#2563eb",
                                        border: "1px solid #bfdbfe",
                                        borderRadius: "6px",
                                        padding: "7px 14px",
                                        fontSize: "0.82rem",
                                        fontWeight: "600",
                                        display: "flex",
                                        alignItems: "center",
                                        gap: "6px",
                                        cursor: "pointer"
                                    }}
                                    onClick={() => setShowPhotoPicker((prev) => !prev)}
                                    disabled={loading || photoUploading}
                                >
                                    <FaCamera />
                                    <span>{showPhotoPicker ? "Cancel Photo Change" : (currentPhotoUrl ? "Change Photo" : "Upload Photo")}</span>
                                </button>
                            </div>

                            {showPhotoPicker && (
                                <div style={{ marginTop: "16px", paddingTop: "14px", borderTop: "1px solid #e2e8f0" }}>
                                    <div style={{ marginBottom: "10px", fontSize: "0.84rem", fontWeight: "600", color: "#334155" }}>
                                        Select New Photo (Camera or File Upload)
                                    </div>
                                    <WebcamCapture
                                        onCapture={handlePhotoCapture}
                                        onClear={handlePhotoClear}
                                        disabled={loading || photoUploading}
                                        allowUpload={true}
                                    />
                                    {capturedPhoto && (
                                        <div style={{ display: "flex", justifyContent: "flex-end", gap: "10px", marginTop: "12px" }}>
                                            <button
                                                type="button"
                                                style={{
                                                    padding: "6px 14px",
                                                    borderRadius: "6px",
                                                    background: "#f1f5f9",
                                                    border: "1px solid #cbd5e1",
                                                    fontSize: "0.82rem",
                                                    fontWeight: "500",
                                                    cursor: "pointer"
                                                }}
                                                onClick={() => {
                                                    setCapturedPhoto(null);
                                                    setShowPhotoPicker(false);
                                                }}
                                                disabled={photoUploading}
                                            >
                                                Cancel
                                            </button>
                                            <button
                                                type="button"
                                                style={{
                                                    padding: "6px 16px",
                                                    borderRadius: "6px",
                                                    background: "#2563eb",
                                                    color: "#fff",
                                                    border: "none",
                                                    fontSize: "0.82rem",
                                                    fontWeight: "600",
                                                    cursor: "pointer"
                                                }}
                                                onClick={handleSavePhotoOnly}
                                                disabled={photoUploading}
                                            >
                                                {photoUploading ? "Saving Photo..." : "Save Photo Now"}
                                            </button>
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    )}


                    {/* ROLE + DEPARTMENT */}
                    <div className="dev-form-row">
                        <div className="dev-form-group">
                            <label>Role *</label>
                            <select
                                name="role"
                                value={formData.role}
                                onChange={handleChange}
                                disabled={loading}
                            >
                                <option value="">Select Role</option>
                                {ROLES.map((r) => (
                                    <option key={r.value} value={r.value}>{r.label}</option>
                                ))}
                            </select>
                        </div>
                        <div className="dev-form-group">
                            <label>Department *</label>
                            <select
                                name="departmentId"
                                value={formData.departmentId}
                                onChange={handleChange}
                                disabled={loading}
                            >
                                <option value="">Select Department</option>
                                {departments.map((d) => (
                                    <option key={d.id} value={d.id}>{d.name}</option>
                                ))}
                            </select>
                        </div>
                    </div>


                    {/* ========================================================
                        FACULTY → HOD MAPPING
                    ======================================================== */}
                    {formData.role && formData.role !== "DEVELOPER" && formData.role !== "VC" && (
                        <>
                    {formData.role === "FACULTY" && (
                        <>
                            <div className="dev-form-section-title dev-form-section-faculty">
                                <FaUserGraduate />
                                <span>Faculty → HOD Mapping</span>
                            </div>

                            <div className="dev-form-row">
                                <div className="dev-form-group">
                                    <label>Primary Approver (HOD/TPO) *</label>
                                    <select
                                        name="leaveApproverId"
                                        value={formData.leaveApproverId}
                                        onChange={handleChange}
                                        disabled={loading}
                                    >
                                        <option value="">Select HOD / TPO</option>
                                        {approvers
                                            .filter((a) => ["HOD", "TPO"].includes(a.role))
                                            .map((a) => (
                                                <option key={a.id} value={a.id}>
                                                    {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role} - {a.departmentName})
                                                </option>
                                            ))}
                                    </select>
                                </div>
                                <div className="dev-form-group">
                                    <label>Secondary Approver (Optional)</label>
                                    <select
                                        name="secondaryApproverId"
                                        value={formData.secondaryApproverId}
                                        onChange={handleChange}
                                        disabled={loading}
                                    >
                                        <option value="">Select Principal / VC</option>
                                        {approvers
                                            .filter((a) => ["PRINCIPAL", "VC"].includes(a.role))
                                            .map((a) => (
                                                <option key={a.id} value={a.id}>
                                                    {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                </option>
                                            ))}
                                    </select>
                                </div>
                            </div>

                            <div className="dev-form-info">
                                <FaInfoCircle />
                                <span>
                                    Faculty leave requests will be sent to the selected HOD/TPO for approval.
                                    Secondary approver is used when HOD applies for leave.
                                </span>
                            </div>
                        </>
                    )}

                    {/* ========================================================
                        HOD → PRINCIPAL/VC MAPPING
                    ======================================================== */}
                    {formData.role === "HOD" && (
                        <>
                            <div className="dev-form-section-title dev-form-section-hod">
                                <FaUserShield />
                                <span>HOD → Principal/VC Mapping</span>
                            </div>

                            <div className="dev-form-row">
                                <div className="dev-form-group">
                                    <label>Approves HOD Leave (Principal/VC) *</label>
                                    <select
                                        name="leaveApproverId"
                                        value={formData.leaveApproverId}
                                        onChange={handleChange}
                                        disabled={loading}
                                    >
                                        <option value="">Select Approver</option>
                                        {approvers
                                            .filter((a) => ["PRINCIPAL", "VC"].includes(a.role))
                                            .map((a) => (
                                                <option key={a.id} value={a.id}>
                                                    {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                </option>
                                            ))}
                                    </select>
                                </div>
                                <div className="dev-form-group">
                                    <label>Secondary Approver (Optional)</label>
                                    <select
                                        name="secondaryApproverId"
                                        value={formData.secondaryApproverId}
                                        onChange={handleChange}
                                        disabled={loading}
                                    >
                                        <option value="">Select Secondary</option>
                                        {approvers
                                            .filter((a) => ["PRINCIPAL", "VC"].includes(a.role) && String(a.id) !== String(formData.leaveApproverId))
                                            .map((a) => (
                                                <option key={a.id} value={a.id}>
                                                    {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                </option>
                                            ))}
                                    </select>
                                </div>
                            </div>

                            <div className="dev-form-info">
                                <FaInfoCircle />
                                <span>
                                    HOD leave requests will be sent to Principal/VC for approval.
                                </span>
                            </div>

                            {/* FACULTY MAPPING UNDER HOD */}
                            {!isEdit && departmentFaculty.length > 0 && (
                                <>
                                    <div className="dev-form-section-title dev-form-section-faculty">
                                        <FaUserGraduate />
                                        <span>Map Faculty Under This HOD</span>
                                        <span className="dev-faculty-selected-count">
                                            {selectedFacultyIds.length} selected
                                        </span>
                                    </div>

                                    <div className="dev-faculty-select-all">
                                        <label className="dev-faculty-checkbox-item dev-faculty-select-all-label">
                                            <input
                                                type="checkbox"
                                                checked={departmentFaculty.length > 0 && departmentFaculty.every((f) => selectedFacultyIds.includes(f.id))}
                                                onChange={toggleAllFaculty}
                                                disabled={loading}
                                            />
                                            <span>Select All ({departmentFaculty.length})</span>
                                        </label>
                                    </div>

                                    <div className="dev-faculty-list">
                                        {departmentFaculty.map((f) => (
                                            <label className="dev-faculty-checkbox-item" key={f.id}>
                                                <input
                                                    type="checkbox"
                                                    checked={selectedFacultyIds.includes(f.id)}
                                                    onChange={() => toggleFaculty(f.id)}
                                                    disabled={loading}
                                                />
                                                <div className="dev-faculty-checkbox-avatar">
                                                    {f.fullName ? f.fullName.charAt(0).toUpperCase() : "U"}
                                                </div>
                                                <div className="dev-faculty-checkbox-info">
                                                    <span className="dev-faculty-checkbox-name">{f.fullName || `${f.firstName} ${f.lastName}`}</span>
                                                    <span className="dev-faculty-checkbox-email">{f.email}</span>
                                                </div>
                                            </label>
                                        ))}
                                    </div>

                                    <div className="dev-form-info">
                                        <FaInfoCircle />
                                        <span>
                                            Selected Faculty will be mapped under this HOD for attendance monitoring and leave approval.
                                        </span>
                                    </div>
                                </>
                            )}
                        </>
                    )}

                    {/* --- TPO: maps to Principal/VC --- */}
                            {formData.role === "TPO" && (
                                <>
                                    <div className="dev-form-section-title dev-form-section-hod">
                                        <FaUserShield />
                                        <span>TPO → Principal/VC Mapping</span>
                                    </div>

                                    <div className="dev-form-row">
                                        <div className="dev-form-group">
                                            <label>Approves TPO Leave (Principal/VC) *</label>
                                            <select
                                                name="leaveApproverId"
                                                value={formData.leaveApproverId}
                                                onChange={handleChange}
                                                disabled={loading}
                                            >
                                                <option value="">Select Approver</option>
                                                {approvers
                                                    .filter((a) => ["PRINCIPAL", "VC"].includes(a.role))
                                                    .map((a) => (
                                                        <option key={a.id} value={a.id}>
                                                            {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                        </option>
                                                    ))}
                                            </select>
                                        </div>
                                        <div className="dev-form-group">
                                            <label>Secondary Approver (Optional)</label>
                                            <select
                                                name="secondaryApproverId"
                                                value={formData.secondaryApproverId}
                                                onChange={handleChange}
                                                disabled={loading}
                                            >
                                                <option value="">Select Secondary</option>
                                                {approvers
                                                    .filter((a) => ["PRINCIPAL", "VC"].includes(a.role) && String(a.id) !== String(formData.leaveApproverId))
                                                    .map((a) => (
                                                        <option key={a.id} value={a.id}>
                                                            {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                        </option>
                                                    ))}
                                            </select>
                                        </div>
                                    </div>
                                    <div className="dev-form-info">
                                        <FaInfoCircle />
                                        <span>
                                            TPO leave requests will be sent to Principal/VC for approval.
                                        </span>
                                    </div>

                                    {/* FACULTY MAPPING UNDER TPO */}
                                    {departmentFaculty.length > 0 && (
                                        <>
                                            <div className="dev-form-section-title dev-form-section-faculty">
                                                <FaUserGraduate />
                                                <span>Map Faculty Under This TPO</span>
                                                <span className="dev-faculty-selected-count">
                                                    {selectedFacultyIds.length} selected
                                                </span>
                                            </div>

                                            <div className="dev-faculty-select-all">
                                                <label className="dev-faculty-checkbox-item dev-faculty-select-all-label">
                                                    <input
                                                        type="checkbox"
                                                        checked={departmentFaculty.length > 0 && departmentFaculty.every((f) => selectedFacultyIds.includes(f.id))}
                                                        onChange={toggleAllFaculty}
                                                        disabled={loading}
                                                    />
                                                    <span>Select All ({departmentFaculty.length})</span>
                                                </label>
                                            </div>

                                            <div className="dev-faculty-list">
                                                {departmentFaculty.map((f) => (
                                                    <label className="dev-faculty-checkbox-item" key={f.id}>
                                                        <input
                                                            type="checkbox"
                                                            checked={selectedFacultyIds.includes(f.id)}
                                                            onChange={() => toggleFaculty(f.id)}
                                                            disabled={loading}
                                                        />
                                                        <div className="dev-faculty-checkbox-avatar">
                                                            {f.fullName ? f.fullName.charAt(0).toUpperCase() : "U"}
                                                        </div>
                                                        <div className="dev-faculty-checkbox-info">
                                                            <span className="dev-faculty-checkbox-name">{f.fullName || `${f.firstName} ${f.lastName}`}</span>
                                                            <span className="dev-faculty-checkbox-email">{f.email}</span>
                                                        </div>
                                                    </label>
                                                ))}
                                            </div>

                                            <div className="dev-form-info">
                                                <FaInfoCircle />
                                                <span>
                                                    Selected Faculty will be mapped under this TPO for attendance monitoring and leave approval.
                                                </span>
                                            </div>
                                        </>
                                    )}
                                </>
                            )}

                            {/* --- PRINCIPAL: maps to VC --- */}
                            {formData.role === "PRINCIPAL" && (
                                <>
                                    <div className="dev-form-section-title dev-form-section-hod">
                                        <FaUserShield />
                                        <span>Principal → VC Mapping</span>
                                    </div>

                                    <div className="dev-form-row">
                                        <div className="dev-form-group">
                                            <label>Approves Principal Leave (VC) *</label>
                                            <select
                                                name="leaveApproverId"
                                                value={formData.leaveApproverId}
                                                onChange={handleChange}
                                                disabled={loading}
                                            >
                                                <option value="">Select VC</option>
                                                {approvers
                                                    .filter((a) => a.role === "VC")
                                                    .map((a) => (
                                                        <option key={a.id} value={a.id}>
                                                            {a.fullName || `${a.firstName} ${a.lastName}`} (VC)
                                                        </option>
                                                    ))}
                                            </select>
                                        </div>
                                        <div className="dev-form-group">
                                            <label>Secondary Approver (Optional)</label>
                                            <select
                                                name="secondaryApproverId"
                                                value={formData.secondaryApproverId}
                                                onChange={handleChange}
                                                disabled={loading}
                                            >
                                                <option value="">Select Secondary</option>
                                                {approvers
                                                    .filter((a) => a.role === "VC" && String(a.id) !== String(formData.leaveApproverId))
                                                    .map((a) => (
                                                        <option key={a.id} value={a.id}>
                                                            {a.fullName || `${a.firstName} ${a.lastName}`} (VC)
                                                        </option>
                                                    ))}
                                            </select>
                                        </div>
                                    </div>
                                    <div className="dev-form-info">
                                        <FaInfoCircle />
                                        <span>
                                            Principal leave requests will be sent to VC for approval.
                                        </span>
                                    </div>

                                    {/* FACULTY MAPPING UNDER PRINCIPAL */}
                                    {departmentFaculty.length > 0 && (
                                        <>
                                            <div className="dev-form-section-title dev-form-section-faculty">
                                                <FaUserGraduate />
                                                <span>Map Faculty Under This Principal</span>
                                                <span className="dev-faculty-selected-count">
                                                    {selectedFacultyIds.length} selected
                                                </span>
                                            </div>

                                            <div className="dev-faculty-select-all">
                                                <label className="dev-faculty-checkbox-item dev-faculty-select-all-label">
                                                    <input
                                                        type="checkbox"
                                                        checked={departmentFaculty.length > 0 && departmentFaculty.every((f) => selectedFacultyIds.includes(f.id))}
                                                        onChange={toggleAllFaculty}
                                                        disabled={loading}
                                                    />
                                                    <span>Select All ({departmentFaculty.length})</span>
                                                </label>
                                            </div>

                                            <div className="dev-faculty-list">
                                                {departmentFaculty.map((f) => (
                                                    <label className="dev-faculty-checkbox-item" key={f.id}>
                                                        <input
                                                            type="checkbox"
                                                            checked={selectedFacultyIds.includes(f.id)}
                                                            onChange={() => toggleFaculty(f.id)}
                                                            disabled={loading}
                                                        />
                                                        <div className="dev-faculty-checkbox-avatar">
                                                            {f.fullName ? f.fullName.charAt(0).toUpperCase() : "U"}
                                                        </div>
                                                        <div className="dev-faculty-checkbox-info">
                                                            <span className="dev-faculty-checkbox-name">{f.fullName || `${f.firstName} ${f.lastName}`}</span>
                                                            <span className="dev-faculty-checkbox-email">{f.email}</span>
                                                        </div>
                                                    </label>
                                                ))}
                                            </div>

                                            <div className="dev-form-info">
                                                <FaInfoCircle />
                                                <span>
                                                    Selected Faculty will be mapped under this Principal for attendance monitoring and leave approval.
                                                </span>
                                            </div>
                                        </>
                                    )}
                                </>
                            )}
                        </>
                    )}


                    {/* ACTIONS */}
                    <div className="dev-form-actions">
                        <button type="button" className="dev-btn-cancel" onClick={onClose} disabled={loading}>
                            Cancel
                        </button>
                        <button
                            type="submit"
                            className="dev-btn-submit"
                            disabled={loading || photoUploading || (!isEdit && formData.role === "FACULTY" && !capturedPhoto)}
                            title={!isEdit && formData.role === "FACULTY" && !capturedPhoto ? "Profile photo is required." : undefined}
                        >
                            {loading ? (
                                <>
                                    <span className="dev-btn-spinner"></span>
                                    {isEdit ? "Saving..." : "Creating..."}
                                </>
                            ) : isEdit ? (
                                <>
                                    <FaCheck />
                                    Save Changes
                                </>
                            ) : (
                                <>
                                    <FaUserPlus />
                                    Create Account
                                </>
                            )}
                        </button>
                    </div>

                </form>

            </div>
        </div>
    );
}


// =========================================================
// ACCOUNT CARD
// =========================================================

function AccountCard({ account, onDelete, onEdit, onActivate, onResend, onResetPassword }) {

    const [showConfirm, setShowConfirm] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const [busyAction, setBusyAction] = useState("");

    const status = account.activationStatus || (account.isActive ? "ACTIVE" : "INACTIVE");

    const runAction = async (key, action) => {
        try {
            setBusyAction(key);
            await action();
        } finally {
            setBusyAction("");
        }
    };

    const roleConfig = ROLES.find((r) => r.value === account.role) || ROLES[0];
    const RoleIcon = roleConfig.icon;

    const handleDelete = async () => {
        try {
            setDeleting(true);
            await onDelete(account.id);
            setShowConfirm(false);
        } catch {
            setDeleting(false);
        }
    };

    return (
        <div className="dev-account-card">
            <div className="dev-account-card-top">
                <div className="dev-account-avatar" style={{ background: roleConfig.color + "15", color: roleConfig.color, overflow: "hidden" }}>
                    {account.profilePictureUrl ? (
                        <>
                            <img
                                src={imageSrc(account.profilePictureUrl)}
                                alt={account.fullName || "User photo"}
                                style={{ width: "100%", height: "100%", objectFit: "cover" }}
                                onError={(e) => {
                                    e.currentTarget.style.display = "none";
                                    if (e.currentTarget.nextSibling) e.currentTarget.nextSibling.style.display = "inline";
                                }}
                            />
                            <span style={{ display: "none" }}>
                                {account.fullName ? account.fullName.charAt(0).toUpperCase() : <FaUserCircle />}
                            </span>
                        </>
                    ) : (
                        <span>{account.fullName ? account.fullName.charAt(0).toUpperCase() : <FaUserCircle />}</span>
                    )}
                </div>
                <div className="dev-account-info">
                    <div style={{ display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap" }}>
                        <h3>{account.fullName || `${account.firstName} ${account.lastName}`}</h3>
                        {account.role === "FACULTY" && !account.profilePictureUrl && (
                            <span style={{
                                background: "#fef3c7",
                                color: "#b45309",
                                fontSize: "0.68rem",
                                fontWeight: "600",
                                padding: "1px 6px",
                                borderRadius: "4px",
                                border: "1px solid #fde68a"
                            }} title="Profile photo is required for attendance verification">
                                Photo Required
                            </span>
                        )}
                    </div>
                    <p className="dev-account-email">{account.email}</p>
                </div>
                <div className="dev-account-actions">
                    {showConfirm ? (
                        <div className="dev-confirm-delete">
                            <span>Deactivate?</span>
                            <button onClick={handleDelete} disabled={deleting} className="dev-confirm-yes">
                                {deleting ? <span className="dev-mini-spinner" /> : <FaCheck />}
                            </button>
                            <button onClick={() => setShowConfirm(false)} className="dev-confirm-no">
                                <FaTimes />
                            </button>
                        </div>
                    ) : (
                        <>
                            <button
                                type="button"
                                className="dev-action-btn"
                                title="Edit account"
                                onClick={() => onEdit && onEdit(account)}
                                disabled={busyAction !== ""}
                            >
                                <FaEdit />
                            </button>

                            {status === "PENDING" && (
                                <button
                                    type="button"
                                    className="dev-action-btn"
                                    title="Resend activation email"
                                    onClick={() => runAction("resend", () => onResend(account))}
                                    disabled={busyAction !== ""}
                                >
                                    {busyAction === "resend" ? <span className="dev-mini-spinner" /> : <FaEnvelope />}
                                </button>
                            )}

                            {status === "INACTIVE" && (
                                <button
                                    type="button"
                                    className="dev-action-btn"
                                    title="Activate account"
                                    onClick={() => runAction("activate", () => onActivate(account))}
                                    disabled={busyAction !== ""}
                                >
                                    {busyAction === "activate" ? <span className="dev-mini-spinner" /> : <FaCheck />}
                                </button>
                            )}

                            {status === "ACTIVE" && (
                                <button
                                    type="button"
                                    className="dev-action-btn"
                                    title="Send secure password reset link"
                                    onClick={() => runAction("reset", () => onResetPassword(account))}
                                    disabled={busyAction !== ""}
                                >
                                    {busyAction === "reset" ? <span className="dev-mini-spinner" /> : <FaKey />}
                                </button>
                            )}

                            {status !== "INACTIVE" && (
                                <button
                                    type="button"
                                    className="dev-delete-btn"
                                    title="Deactivate account"
                                    onClick={() => setShowConfirm(true)}
                                >
                                    <FaTrash />
                                </button>
                            )}
                        </>
                    )}
                </div>
            </div>

            <div className="dev-account-card-details">
                <div className="dev-account-detail">
                    <FaIdBadge />
                    <span>{account.employeeId || "N/A"}</span>
                </div>
                <div className="dev-account-detail">
                    <FaBuilding />
                    <span>{account.departmentName || "N/A"}</span>
                </div>
                <div className="dev-account-detail">
                    <FaPhoneAlt />
                    <span>{account.phone || "N/A"}</span>
                </div>
                <div className="dev-account-detail">
                    <FaHistory />
                    <span>{account.createdAt ? dayjs(account.createdAt).format("DD MMM YYYY") : "N/A"}</span>
                </div>
            </div>

            <div className="dev-account-card-footer">
                <span className="dev-account-role-badge" style={{ background: roleConfig.color + "15", color: roleConfig.color }}>
                    <RoleIcon />
                    {roleConfig.label}
                </span>
                <span className="dev-account-role-badge" style={STATUS_STYLES[status] || STATUS_STYLES.INACTIVE}>
                    {STATUS_LABELS[status] || status}
                </span>
            </div>

            {/* LEAVE APPROVER INFO (for all roles except DEVELOPER & VC) */}
            {account.role !== "DEVELOPER" && account.role !== "VC" && (
                <div className="dev-account-approver-info">
                    <div className="dev-account-approver-row">
                        <FaUserShield className="dev-account-approver-icon" />
                        <div>
                            <span className="dev-account-approver-label">
                                {account.role === "FACULTY"
                                    ? "HOD/TPO Approver"
                                    : account.role === "HOD" || account.role === "TPO"
                                    ? "Principal/VC Approver"
                                    : "VC Approver"}
                            </span>
                            <span className="dev-account-approver-value">
                                {account.leaveApproverName || "Not assigned"}
                            </span>
                        </div>
                    </div>
                    {account.secondaryApproverName && (
                        <div className="dev-account-approver-row">
                            <FaUserShield className="dev-account-approver-icon" />
                            <div>
                                <span className="dev-account-approver-label">Secondary Approver</span>
                                <span className="dev-account-approver-value">
                                    {account.secondaryApproverName}
                                </span>
                            </div>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}


// =========================================================
// DEPARTMENT CARD (management tab — same card design)
// =========================================================

function DepartmentCard({ department, onEdit, onToggleActive, onDelete }) {

    const [showConfirm, setShowConfirm] = useState(false);
    const [deleting, setDeleting] = useState(false);

    const hasUsers = (department.totalUsers || 0) > 0;

    const handleDelete = async () => {
        try {
            setDeleting(true);
            await onDelete(department.id);
            setShowConfirm(false);
        } catch {
            setDeleting(false);
        }
    };

    return (
        <div className="dev-account-card">
            <div className="dev-account-card-top">
                <div className="dev-account-avatar" style={{ background: "#6366f115", color: "#6366f1" }}>
                    <FaBuilding />
                </div>
                <div className="dev-account-info">
                    <h3>{department.name}</h3>
                    <p className="dev-account-email">
                        {department.code}{department.description ? ` — ${department.description}` : ""}
                    </p>
                </div>
                <div className="dev-account-actions">
                    {showConfirm ? (
                        <div className="dev-confirm-delete">
                            <span>{hasUsers ? "Deactivate?" : "Delete?"}</span>
                            <button onClick={handleDelete} disabled={deleting} className="dev-confirm-yes">
                                {deleting ? <span className="dev-mini-spinner" /> : <FaCheck />}
                            </button>
                            <button onClick={() => setShowConfirm(false)} className="dev-confirm-no">
                                <FaTimes />
                            </button>
                        </div>
                    ) : (
                        <>
                            <button
                                type="button"
                                className="dev-action-btn"
                                title="Edit department"
                                onClick={() => onEdit(department)}
                            >
                                <FaEdit />
                            </button>
                            <button
                                type="button"
                                className="dev-action-btn"
                                title={department.isActive ? "Deactivate department" : "Activate department"}
                                onClick={() => onToggleActive(department)}
                            >
                                {department.isActive ? <FaTimes /> : <FaCheck />}
                            </button>
                            <button
                                type="button"
                                className="dev-delete-btn"
                                title="Delete department"
                                onClick={() => setShowConfirm(true)}
                            >
                                <FaTrash />
                            </button>
                        </>
                    )}
                </div>
            </div>

            <div className="dev-account-card-details">
                <div className="dev-account-detail">
                    <FaUserGraduate />
                    <span>{department.facultyCount || 0} faculty</span>
                </div>
                <div className="dev-account-detail">
                    <FaUsers />
                    <span>{department.totalUsers || 0} users</span>
                </div>
            </div>

            <div className="dev-account-card-footer">
                <span
                    className="dev-account-role-badge"
                    style={department.isActive ? STATUS_STYLES.ACTIVE : { background: "#f1f5f9", color: "#64748b" }}
                >
                    {department.isActive ? "Active" : "Inactive"}
                </span>
            </div>
        </div>
    );
}


// =========================================================
// DEPARTMENT MODAL (create / edit)
// =========================================================

function DepartmentModal({ onClose, onSuccess, department }) {

    const isEdit = Boolean(department);

    const [formData, setFormData] = useState(() => ({
        name: department?.name || "",
        code: department?.code || "",
        description: department?.description || "",
    }));
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");

    const handleChange = (e) => {
        const { name, value } = e.target;
        setFormData((prev) => ({ ...prev, [name]: value }));
        setError("");
        setSuccess("");
    };

    const handleSubmit = async (e) => {
        e.preventDefault();

        const name = formData.name.trim();
        const code = formData.code.trim();

        if (name.length < 3) {
            setError("Department name must be at least 3 characters.");
            return;
        }
        if (!code) {
            setError("Department code is required.");
            return;
        }
        if (!/^[A-Za-z0-9-]+$/.test(code)) {
            setError("Code may only contain letters, numbers and hyphens.");
            return;
        }

        try {
            setLoading(true);
            setError("");

            const payload = {
                name,
                code,
                description: formData.description.trim() || undefined,
            };

            const res = isEdit
                ? await developerAPI.updateDepartment(department.id, payload)
                : await developerAPI.createDepartment(payload);

            setSuccess(res.data?.name
                ? (isEdit ? "Department updated successfully!" : "Department created successfully!")
                : "Saved successfully!");

            setTimeout(() => {
                onSuccess();
                onClose();
            }, 900);
        } catch (err) {
            console.error("Save department error:", err);
            setError(err.response?.data?.message || "Failed to save department. Please try again.");
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="dev-modal-overlay" onClick={onClose}>
            <div className="dev-modal" onClick={(e) => e.stopPropagation()}>

                <div className="dev-modal-header">
                    <div className="dev-modal-header-left">
                        <FaBuilding className="dev-modal-header-icon" />
                        <div>
                            <h2>{isEdit ? "Edit Department" : "Create New Department"}</h2>
                            <p>{isEdit ? "Update department details" : "Add a department to the system"}</p>
                        </div>
                    </div>
                    <button className="dev-modal-close" onClick={onClose} type="button">
                        <FaTimes />
                    </button>
                </div>

                {error && (
                    <div className="dev-alert dev-alert-error">
                        <FaInfoCircle /><span>{error}</span>
                    </div>
                )}
                {success && (
                    <div className="dev-alert dev-alert-success">
                        <FaCheck /><span>{success}</span>
                    </div>
                )}

                <form className="dev-form" onSubmit={handleSubmit}>
                    <div className="dev-form-group">
                        <label>Department Name *</label>
                        <input
                            type="text"
                            name="name"
                            value={formData.name}
                            onChange={handleChange}
                            placeholder="e.g. Computer Science & Engineering"
                            disabled={loading}
                        />
                    </div>

                    <div className="dev-form-group">
                        <label>Department Code *</label>
                        <input
                            type="text"
                            name="code"
                            value={formData.code}
                            onChange={handleChange}
                            placeholder="e.g. CSE"
                            disabled={loading || isEdit}
                        />
                    </div>

                    <div className="dev-form-group">
                        <label>Description</label>
                        <input
                            type="text"
                            name="description"
                            value={formData.description}
                            onChange={handleChange}
                            placeholder="Short description (optional)"
                            disabled={loading}
                        />
                    </div>

                    <div className="dev-form-info">
                        <FaInfoCircle />
                        <span>
                            Department codes must be unique. Departments with linked users can only be deactivated,
                            never deleted, so no data is lost.
                        </span>
                    </div>

                    <div className="dev-form-actions">
                        <button type="button" className="dev-btn-cancel" onClick={onClose} disabled={loading}>
                            Cancel
                        </button>
                        <button type="submit" className="dev-btn-submit" disabled={loading}>
                            {loading ? (
                                <>
                                    <span className="dev-btn-spinner"></span>
                                    {isEdit ? "Saving..." : "Creating..."}
                                </>
                            ) : isEdit ? (
                                <>
                                    <FaCheck />
                                    Save Changes
                                </>
                            ) : (
                                <>
                                    <FaBuilding />
                                    Create Department
                                </>
                            )}
                        </button>
                    </div>
                </form>

            </div>
        </div>
    );
}


// =========================================================
// MAIN DEVELOPER DASHBOARD
// =========================================================

function DeveloperDashboard() {

    const navigate = useNavigate();
    const location = useLocation();
    const { user, logout } = useAuth();

    // STATE — lists, filters and backend pagination
    const [accounts, setAccounts] = useState([]);
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const [totalElements, setTotalElements] = useState(0);
    const pageSize = 24;

    const [departments, setDepartments] = useState([]);
    const [allDepartments, setAllDepartments] = useState([]);
    const [approvers, setApprovers] = useState([]);
    const [facultyList, setFacultyList] = useState([]);
    const [loading, setLoading] = useState(true);
    const [statsLoading, setStatsLoading] = useState(true);
    const [departmentsLoading, setDepartmentsLoading] = useState(true);
    const [searchQuery, setSearchQuery] = useState("");
    const [debouncedQuery, setDebouncedQuery] = useState("");
    const [roleFilter, setRoleFilter] = useState("ALL");
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [activeTab, setActiveTab] = useState("ACCOUNTS");
    const [showCreateModal, setShowCreateModal] = useState(false);
    const [editingAccount, setEditingAccount] = useState(null);
    const [showDepartmentModal, setShowDepartmentModal] = useState(false);
    const [editingDepartment, setEditingDepartment] = useState(null);
    const [apiError, setApiError] = useState("");
    const [notice, setNotice] = useState("");
    const [profileDropdownOpen, setProfileDropdownOpen] = useState(false);
    const profileDropdownRef = useRef(null);

    useEffect(() => {
        const handleClickOutside = (event) => {
            if (profileDropdownRef.current && !profileDropdownRef.current.contains(event.target)) {
                setProfileDropdownOpen(false);
            }
        };
        document.addEventListener("mousedown", handleClickOutside);
        return () => document.removeEventListener("mousedown", handleClickOutside);
    }, []);

    // STATS — every value comes from GET /developer/stats (real MySQL data)
    const [stats, setStats] = useState({
        total: 0,
        faculty: 0,
        admins: 0,
        departments: 0,
        active: 0,
        inactive: 0,
        pendingActivations: 0,
        roleCounts: {},
        departmentWise: [],
        recentAccounts: [],
        recentActions: [],
        attendance: null,
    });


    // ---- FEEDBACK HELPERS ----
    const showNotice = (message) => {
        setApiError("");
        setNotice(message);
        setTimeout(() => setNotice(""), 6000);
    };

    const showError = (err, fallback) => {
        setNotice("");
        setApiError(err?.response?.data?.message || fallback);
    };


    // ---- LOAD DASHBOARD STATS ----
    const loadStats = async () => {
        try {
            const res = await developerAPI.getStats();
            setStats((prev) => ({ ...prev, ...res.data }));
        } catch (err) {
            console.error("Load stats error:", err);
            setApiError(err.response?.data?.message || "Unable to load dashboard statistics.");
        } finally {
            setStatsLoading(false);
        }
    };


    // ---- LOAD ACCOUNTS (backend paginated / sorted / filtered) ----
    const loadAccounts = async () => {
        try {
            setLoading(true);
            const res = await developerAPI.getAccountsPage({
                q: debouncedQuery || undefined,
                role: roleFilter !== "ALL" ? roleFilter : undefined,
                status: statusFilter !== "ALL" ? statusFilter : undefined,
                page,
                size: pageSize,
                sort: "createdAt",
                direction: "desc",
            });
            const data = res.data || {};
            setAccounts(Array.isArray(data.content) ? data.content : []);
            setTotalPages(data.totalPages || 1);
            setTotalElements(data.totalElements || 0);
        } catch (err) {
            console.error("Load accounts error:", err);
            if (err.response?.status === 401 || err.response?.status === 403) {
                setApiError("Authentication session expired or unauthorized. Please re-login with the System Developer account (Dev@college.edu).");
            } else {
                setApiError(err.response?.data?.message || "Unable to load accounts.");
            }
        } finally {
            setLoading(false);
        }
    };


    // ---- LOAD DEPARTMENTS (no hardcoded fallbacks — errors are surfaced) ----
    const loadDepartments = async () => {
        try {
            const res = await developerAPI.getDepartments();
            setDepartments(Array.isArray(res.data) ? res.data : []);
        } catch (err) {
            console.error("Load departments error:", err);
            setDepartments([]);
            setApiError(err.response?.data?.message || "Unable to load departments.");
        }
    };

    const loadAllDepartments = async () => {
        try {
            setDepartmentsLoading(true);
            const res = await developerAPI.getAllDepartments();
            setAllDepartments(Array.isArray(res.data) ? res.data : []);
        } catch (err) {
            console.error("Load all departments error:", err);
            setAllDepartments([]);
            setApiError(err.response?.data?.message || "Unable to load departments.");
        } finally {
            setDepartmentsLoading(false);
        }
    };


    // ---- LOAD APPROVERS (HODs/TPOs/Principals/VCs) ----
    const loadApprovers = async () => {
        try {
            const res = await developerAPI.getApprovers();
            setApprovers(Array.isArray(res.data) ? res.data : []);
        } catch (err) {
            console.error("Load approvers error:", err);
            setApprovers([]);
        }
    };


    // ---- LOAD FACULTY LIST (used by the create-account mapping UI) ----
    const loadFacultyList = async () => {
        try {
            const res = await developerAPI.getAccountsPage({
                role: "FACULTY",
                size: 500,
                sort: "firstName",
                direction: "asc",
            });
            setFacultyList(res.data?.content || []);
        } catch (err) {
            console.error("Load faculty list error:", err);
            setFacultyList([]);
        }
    };


    // ---- REFRESH AFTER ANY MUTATION ----
    const refreshAfterMutation = () => {
        loadStats();
        loadAccounts();
        loadFacultyList();
    };


    // ---- ACCOUNT ACTIONS ----
    const handleDelete = async (accountId) => {
        try {
            const res = await developerAPI.deleteAccount(accountId);
            showNotice(res.data?.message || "Account deactivated. Attendance and leave history preserved.");
            refreshAfterMutation();
        } catch (err) {
            console.error("Deactivate error:", err);
            showError(err, "Failed to deactivate account.");
            throw err;
        }
    };

    const handleActivate = async (account) => {
        try {
            const res = await developerAPI.activateAccount(account.id);
            showNotice(res.data?.message || "Account activated.");
            refreshAfterMutation();
        } catch (err) {
            console.error("Activate error:", err);
            showError(err, "Failed to activate account.");
        }
    };

    const handleResendActivation = async (account) => {
        try {
            const res = await developerAPI.resendActivation(account.id);
            showNotice(res.data?.message || "Activation email sent.");
            refreshAfterMutation();
        } catch (err) {
            console.error("Resend activation error:", err);
            showError(err, "Failed to send the activation email.");
            refreshAfterMutation();
        }
    };

    const handleResetPassword = async (account) => {
        try {
            const res = await developerAPI.resetPassword(account.id);
            showNotice(res.data?.message || "Password reset link sent.");
            refreshAfterMutation();
        } catch (err) {
            console.error("Reset password error:", err);
            showError(err, "Failed to send the password reset email.");
            refreshAfterMutation();
        }
    };

    const handleEditAccount = (account) => {
        setApiError("");
        setNotice("");
        setEditingAccount(account);
        setShowCreateModal(true);
    };


    // ---- DEPARTMENT ACTIONS ----
    const handleToggleDepartment = async (department) => {
        try {
            const res = await developerAPI.updateDepartment(department.id, {
                name: department.name,
                code: department.code,
                description: department.description,
                isActive: !department.isActive,
            });
            showNotice(res.data?.name
                ? `Department ${res.data.isActive ? "activated" : "deactivated"}: ${res.data.name}`
                : "Department updated.");
            loadAllDepartments();
            loadDepartments();
            loadStats();
        } catch (err) {
            console.error("Toggle department error:", err);
            showError(err, "Failed to update department.");
        }
    };

    const handleDeleteDepartment = async (departmentId) => {
        try {
            const res = await developerAPI.deleteDepartment(departmentId);
            showNotice(res.data?.message || "Department removed.");
            loadAllDepartments();
            loadDepartments();
            loadStats();
        } catch (err) {
            console.error("Delete department error:", err);
            showError(err, "Failed to delete department.");
            throw err;
        }
    };


    // ---- DEBOUNCED SEARCH (backend q parameter) ----
    useEffect(() => {
        const timer = setTimeout(() => setDebouncedQuery(searchQuery), 350);
        return () => clearTimeout(timer);
    }, [searchQuery]);

    // Reset to the first page whenever a filter changes
    useEffect(() => {
        setPage(0);
    }, [debouncedQuery, roleFilter, statusFilter]);

    // ---- INITIAL LOAD ----
    useEffect(() => {
        if (location.state?.notice) {
            showNotice(location.state.notice);
            window.history.replaceState({}, document.title);
        }
        loadStats();
        loadDepartments();
        loadAllDepartments();
        loadApprovers();
        loadFacultyList();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // ---- LOAD PAGE WHEN FILTERS / PAGE CHANGE ----
    useEffect(() => {
        loadAccounts();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [page, debouncedQuery, roleFilter, statusFilter]);


    // ---- RENDER ----
    return (
        <div className="dev-dashboard">

            {/* HEADER */}
            <header className="dev-header">
                <div className="dev-header-left" ref={profileDropdownRef} style={{ position: "relative" }}>
                    <button
                        type="button"
                        className="dev-profile-btn"
                        aria-label="Developer Profile Menu"
                        aria-expanded={profileDropdownOpen}
                        onClick={() => setProfileDropdownOpen((prev) => !prev)}
                        title={`System Developer: ${user?.fullName || "System Developer"} (${user?.email || "Dev@college.edu"})`}
                    >
                        <FaUserCircle />
                    </button>

                    {profileDropdownOpen && (
                        <div className="dev-profile-dropdown" role="menu">
                            <div className="dev-dropdown-user-header">
                                <div className="dev-dropdown-avatar">
                                    <FaUserCircle />
                                </div>
                                <div className="dev-dropdown-meta">
                                    <span className="dev-dropdown-name">{user?.fullName || "System Developer"}</span>
                                    <span className="dev-dropdown-email">{user?.email || "Dev@college.edu"}</span>
                                    <span className="dev-dropdown-role-tag">System Developer</span>
                                </div>
                            </div>
                            <div className="dev-dropdown-divider" />
                            <button
                                type="button"
                                className="dev-dropdown-item"
                                role="menuitem"
                                onClick={() => {
                                    setProfileDropdownOpen(false);
                                    navigate("/dev/profile");
                                }}
                            >
                                <FaUserCircle className="dev-dropdown-icon" />
                                <span>Profile Details</span>
                            </button>
                            <button
                                type="button"
                                className="dev-dropdown-item"
                                role="menuitem"
                                onClick={() => {
                                    setProfileDropdownOpen(false);
                                    navigate("/change-password");
                                }}
                            >
                                <FaKey className="dev-dropdown-icon" />
                                <span>Change Password</span>
                            </button>
                            <div className="dev-dropdown-divider" />
                            <button
                                type="button"
                                className="dev-dropdown-item dev-dropdown-logout"
                                role="menuitem"
                                onClick={() => {
                                    setProfileDropdownOpen(false);
                                    logout();
                                    navigate("/login", { replace: true });
                                }}
                            >
                                <FaSignOutAlt className="dev-dropdown-icon" />
                                <span>Logout</span>
                            </button>
                        </div>
                    )}
                </div>

                <div className="dev-search">
                    <FaSearch className="dev-search-icon" />
                    <input
                        type="text"
                        placeholder="Search by name, email, ID..."
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                    />
                </div>

                <button type="button" className="dev-logout" onClick={() => { logout(); navigate("/login", { replace: true }); }}>
                    <FaSignOutAlt />
                    <span>Logout</span>
                </button>
            </header>


            {/* MAIN CONTENT */}
            <main className="dev-main">

                {/* STATS — real values from GET /developer/stats */}
                <section className="dev-stats">
                    <div className="dev-stat-card">
                        <div className="dev-stat-icon dev-stat-total">
                            <FaUsers />
                        </div>
                        <div className="dev-stat-content">
                            <strong>{statsLoading ? "–" : stats.total}</strong>
                            <span>Total Accounts</span>
                        </div>
                    </div>
                    <div className="dev-stat-card">
                        <div className="dev-stat-icon dev-stat-faculty">
                            <FaUserGraduate />
                        </div>
                        <div className="dev-stat-content">
                            <strong>{statsLoading ? "–" : stats.faculty}</strong>
                            <span>Faculty</span>
                        </div>
                    </div>
                    <div className="dev-stat-card">
                        <div className="dev-stat-icon dev-stat-admin">
                            <FaUserShield />
                        </div>
                        <div className="dev-stat-content">
                            <strong>{statsLoading ? "–" : stats.admins}</strong>
                            <span>Admins</span>
                        </div>
                    </div>
                    <div className="dev-stat-card">
                        <div className="dev-stat-icon dev-stat-dept">
                            <FaBuilding />
                        </div>
                        <div className="dev-stat-content">
                            <strong>{statsLoading ? "–" : stats.departments}</strong>
                            <span>Departments</span>
                        </div>
                    </div>
                    <div className="dev-stat-card">
                        <div className="dev-stat-icon dev-stat-attendance">
                            <FaUserCheck />
                        </div>
                        <div className="dev-stat-content">
                            <strong>{statsLoading ? "–" : stats.active}</strong>
                            <span>Active</span>
                        </div>
                    </div>
                    <div className="dev-stat-card">
                        <div className="dev-stat-icon dev-stat-inactive">
                            <FaUserSlash />
                        </div>
                        <div className="dev-stat-content">
                            <strong>{statsLoading ? "–" : stats.inactive}</strong>
                            <span>Inactive</span>
                        </div>
                    </div>
                    <div className="dev-stat-card">
                        <div className="dev-stat-icon dev-stat-pending">
                            <FaHourglassHalf />
                        </div>
                        <div className="dev-stat-content">
                            <strong>{statsLoading ? "–" : stats.pendingActivations}</strong>
                            <span>Pending Activation</span>
                        </div>
                    </div>
                    <div className="dev-stat-card">
                        <div className="dev-stat-icon dev-stat-faculty">
                            <FaClipboardList />
                        </div>
                        <div className="dev-stat-content">
                            <strong>{statsLoading || !stats.attendance ? "–" : stats.attendance.marked}</strong>
                            <span>Attendance Marked</span>
                        </div>
                    </div>
                </section>


                {/* =====================================================
                    ACCOUNTS TAB
                ===================================================== */}
                {activeTab === "ACCOUNTS" && (
                    <>

                {/* CREATE BUTTON */}
                <section className="dev-create-section">
                    <button
                        type="button"
                        className="dev-create-btn"
                        onClick={() => navigate("/dev/create-account")}
                    >
                        <FaUserPlus />
                        <span>Create New Account</span>
                    </button>
                </section>


                {/* ROLE FILTER TABS + STATUS FILTER */}
                <section className="dev-filter-section">
                    <div className="dev-filter-bar">
                        <div className="dev-filter-tabs">
                            {[
                                { key: "ALL", label: "All", count: stats.total },
                                { key: "FACULTY", label: "Faculty", count: stats.roleCounts?.FACULTY ?? stats.faculty },
                                { key: "HOD", label: "HOD", count: stats.roleCounts?.HOD ?? 0 },
                                { key: "PRINCIPAL", label: "Principal", count: stats.roleCounts?.PRINCIPAL ?? 0 },
                                { key: "VC", label: "VC", count: stats.roleCounts?.VC ?? 0 },
                                { key: "TPO", label: "TPO", count: stats.roleCounts?.TPO ?? 0 },
                            ].map((tab) => (
                                <button
                                    key={tab.key}
                                    type="button"
                                    className={`dev-filter-tab ${roleFilter === tab.key ? "dev-filter-active" : ""}`}
                                    onClick={() => setRoleFilter(tab.key)}
                                >
                                    {tab.label}
                                    <span className="dev-filter-count">{tab.count}</span>
                                </button>
                            ))}
                        </div>

                        <div className="dev-filter-controls">
                            <select
                                className="dev-filter-select"
                                value={statusFilter}
                                onChange={(e) => setStatusFilter(e.target.value)}
                                aria-label="Filter by account status"
                            >
                                <option value="ALL">All statuses</option>
                                <option value="ACTIVE">Active</option>
                                <option value="INACTIVE">Inactive</option>
                                <option value="PENDING">Pending activation</option>
                            </select>
                        </div>
                    </div>
                </section>


                {/* FEEDBACK */}
                {apiError && (
                    <div className="dev-alert dev-alert-error">
                        <FaInfoCircle /><span>{apiError}</span>
                    </div>
                )}
                {notice && (
                    <div className="dev-alert dev-alert-success">
                        <FaCheck /><span>{notice}</span>
                    </div>
                )}


                {/* ACCOUNT LIST — backend paginated */}
                <section className="dev-accounts-section">
                    <div className="dev-section-heading">
                        <h2>
                            {roleFilter === "ALL" ? "All Accounts" : ROLES.find((r) => r.value === roleFilter)?.label || roleFilter}
                        </h2>
                        <span className="dev-account-count">
                            {loading ? "Loading…" : `${totalElements} account${totalElements !== 1 ? "s" : ""}`}
                        </span>
                    </div>

                    {loading ? (
                        <div className="dev-loading">
                            <div className="dev-spinner"></div>
                            <span>Loading accounts...</span>
                        </div>
                    ) : accounts.length > 0 ? (
                        <>
                            <div className="dev-accounts-grid">
                                {accounts.map((account) => (
                                    <AccountCard
                                        key={account.id}
                                        account={account}
                                        onDelete={handleDelete}
                                        onEdit={handleEditAccount}
                                        onActivate={handleActivate}
                                        onResend={handleResendActivation}
                                        onResetPassword={handleResetPassword}
                                    />
                                ))}
                            </div>

                            {totalPages > 1 && (
                                <div className="dev-pagination">
                                    <button
                                        type="button"
                                        className="dev-page-btn"
                                        onClick={() => setPage((p) => Math.max(0, p - 1))}
                                        disabled={page === 0}
                                    >
                                        <FaChevronLeft />
                                        Prev
                                    </button>
                                    <span className="dev-page-info">
                                        Page {page + 1} of {totalPages}
                                    </span>
                                    <button
                                        type="button"
                                        className="dev-page-btn"
                                        onClick={() => setPage((p) => p + 1)}
                                        disabled={page >= totalPages - 1}
                                    >
                                        Next
                                        <FaChevronRight />
                                    </button>
                                </div>
                            )}
                        </>
                    ) : (
                        <div className="dev-empty">
                            <FaClipboardList className="dev-empty-icon" />
                            <h3>No Accounts Found</h3>
                            <p>
                                {searchQuery || roleFilter !== "ALL" || statusFilter !== "ALL"
                                    ? "No accounts match your search and filters."
                                    : "No accounts in this category yet."}
                            </p>
                        </div>
                    )}
                </section>


                {/* SYSTEM STATUS — today's attendance from attendance_logs */}
                <section className="dev-accounts-section">
                    <div className="dev-section-heading">
                        <h2>System Status</h2>
                        <span className="dev-account-count">
                            {stats.attendance ? dayjs(stats.attendance.date).format("DD MMM YYYY") : "Today's attendance"}
                        </span>
                    </div>

                    {statsLoading ? (
                        <div className="dev-loading">
                            <div className="dev-spinner"></div>
                            <span>Loading system status...</span>
                        </div>
                    ) : stats.attendance ? (
                        <div className="dev-status-chips">
                            <div className="dev-status-chip">
                                <strong>{stats.attendance.activeFaculty}</strong>
                                <span>Active Faculty</span>
                            </div>
                            <div className="dev-status-chip">
                                <strong>{stats.attendance.marked}</strong>
                                <span>Marked Today</span>
                            </div>
                            <div className="dev-status-chip">
                                <strong>{stats.attendance.present}</strong>
                                <span>Present</span>
                            </div>
                            <div className="dev-status-chip">
                                <strong>{stats.attendance.late}</strong>
                                <span>Late</span>
                            </div>
                            <div className="dev-status-chip">
                                <strong>{stats.attendance.absent}</strong>
                                <span>Absent</span>
                            </div>
                            <div className="dev-status-chip">
                                <strong>{stats.attendance.onLeave}</strong>
                                <span>On Leave</span>
                            </div>
                        </div>
                    ) : (
                        <div className="dev-empty">
                            <FaClipboardList className="dev-empty-icon" />
                            <h3>No Attendance Status</h3>
                            <p>Attendance status will appear once faculty start punching in.</p>
                        </div>
                    )}
                </section>


                {/* DEPARTMENT-WISE USER STATISTICS */}
                <section className="dev-accounts-section">
                    <div className="dev-section-heading">
                        <h2>Department-wise Users</h2>
                        <span className="dev-account-count">
                            {stats.departmentWise?.length || 0} department{(stats.departmentWise?.length || 0) !== 1 ? "s" : ""}
                        </span>
                    </div>

                    {statsLoading ? (
                        <div className="dev-loading">
                            <div className="dev-spinner"></div>
                            <span>Loading departments...</span>
                        </div>
                    ) : stats.departmentWise?.length ? (
                        <div className="dev-list-rows">
                            {stats.departmentWise.map((dept) => (
                                <div className="dev-list-row" key={dept.departmentId}>
                                    <FaBuilding className="dev-list-row-icon" />
                                    <div className="dev-list-row-main">
                                        <span className="dev-list-row-title">{dept.name}</span>
                                        <span className="dev-list-row-sub">
                                            {dept.code} · {dept.isActive ? "Active" : "Inactive"}
                                        </span>
                                    </div>
                                    <span className="dev-list-row-value">{dept.facultyCount} faculty</span>
                                    <span className="dev-list-row-meta">
                                        {dept.totalUsers} users · {dept.activeUsers} active
                                    </span>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div className="dev-empty">
                            <FaBuilding className="dev-empty-icon" />
                            <h3>No Departments</h3>
                        </div>
                    )}
                </section>


                {/* RECENT ACTIVITY — audit trail */}
                <section className="dev-accounts-section">
                    <div className="dev-section-heading">
                        <h2>Recent Activity</h2>
                        <span className="dev-account-count">Audit trail</span>
                    </div>

                    {statsLoading ? (
                        <div className="dev-loading">
                            <div className="dev-spinner"></div>
                            <span>Loading activity...</span>
                        </div>
                    ) : stats.recentActions?.length ? (
                        <div className="dev-list-rows">
                            {stats.recentActions.map((entry) => (
                                <div className="dev-list-row" key={entry.id}>
                                    <FaHistory className="dev-list-row-icon" />
                                    <div className="dev-list-row-main">
                                        <span className="dev-list-row-title">
                                            {ACTION_LABELS[entry.action] || entry.action}
                                        </span>
                                        <span className="dev-list-row-sub">
                                            {entry.targetEmail
                                                ? `${entry.targetEmail}${entry.details ? ` — ${entry.details}` : ""}`
                                                : entry.details || ""}
                                        </span>
                                    </div>
                                    <span className="dev-list-row-meta">{entry.actorEmail || "system"}</span>
                                    <span className="dev-list-row-meta">
                                        {entry.createdAt ? dayjs(entry.createdAt).format("DD MMM, HH:mm") : ""}
                                    </span>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div className="dev-empty">
                            <FaHistory className="dev-empty-icon" />
                            <h3>No Activity Yet</h3>
                            <p>Administrative actions such as account and department changes will appear here.</p>
                        </div>
                    )}
                </section>

                    </>
                )}


                {/* =====================================================
                    DEPARTMENTS TAB
                ===================================================== */}
                {activeTab === "DEPARTMENTS" && (
                    <>

                {apiError && (
                    <div className="dev-alert dev-alert-error">
                        <FaInfoCircle /><span>{apiError}</span>
                    </div>
                )}
                {notice && (
                    <div className="dev-alert dev-alert-success">
                        <FaCheck /><span>{notice}</span>
                    </div>
                )}

                <section className="dev-create-section">
                    <button
                        type="button"
                        className="dev-create-btn"
                        onClick={() => { setEditingDepartment(null); setShowDepartmentModal(true); }}
                    >
                        <FaBuilding />
                        <span>Create New Department</span>
                    </button>
                </section>

                <section className="dev-accounts-section">
                    <div className="dev-section-heading">
                        <h2>Departments</h2>
                        <span className="dev-account-count">
                            {allDepartments.length} department{allDepartments.length !== 1 ? "s" : ""}
                        </span>
                    </div>

                    {departmentsLoading ? (
                        <div className="dev-loading">
                            <div className="dev-spinner"></div>
                            <span>Loading departments...</span>
                        </div>
                    ) : allDepartments.length > 0 ? (
                        <div className="dev-accounts-grid">
                            {allDepartments.map((dept) => (
                                <DepartmentCard
                                    key={dept.id}
                                    department={dept}
                                    onEdit={(d) => { setApiError(""); setNotice(""); setEditingDepartment(d); setShowDepartmentModal(true); }}
                                    onToggleActive={handleToggleDepartment}
                                    onDelete={handleDeleteDepartment}
                                />
                            ))}
                        </div>
                    ) : (
                        <div className="dev-empty">
                            <FaBuilding className="dev-empty-icon" />
                            <h3>No Departments Found</h3>
                            <p>Create your first department to get started.</p>
                        </div>
                    )}
                </section>

                    </>
                )}

            </main>


            {/* BOTTOM NAV */}
            <nav className="dev-navigation">
                <button
                    type="button"
                    className={`dev-nav-item ${activeTab === "ACCOUNTS" ? "dev-nav-active" : ""}`}
                    onClick={() => setActiveTab("ACCOUNTS")}
                >
                    <FaUsers />
                    <span>ACCOUNTS</span>
                </button>
                <button
                    type="button"
                    className={`dev-nav-item ${activeTab === "DEPARTMENTS" ? "dev-nav-active" : ""}`}
                    onClick={() => setActiveTab("DEPARTMENTS")}
                >
                    <FaBuilding />
                    <span>DEPARTMENTS</span>
                </button>
            </nav>


            {/* CREATE / EDIT ACCOUNT MODAL */}
            {showCreateModal && (
                <CreateAccountModal
                    onClose={() => { setShowCreateModal(false); setEditingAccount(null); }}
                    onSuccess={refreshAfterMutation}
                    departments={departments}
                    approvers={approvers}
                    facultyList={facultyList}
                    editAccount={editingAccount}
                />
            )}

            {/* CREATE / EDIT DEPARTMENT MODAL */}
            {showDepartmentModal && (
                <DepartmentModal
                    onClose={() => { setShowDepartmentModal(false); setEditingDepartment(null); }}
                    onSuccess={() => { loadAllDepartments(); loadDepartments(); loadStats(); }}
                    department={editingDepartment}
                />
            )}

        </div>
    );
}

export default DeveloperDashboard;
