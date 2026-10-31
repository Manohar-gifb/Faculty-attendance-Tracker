import "./CreateAccountPage.css";

import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
    FaArrowLeft,
    FaUserPlus,
    FaUserGraduate,
    FaUserShield,
    FaEnvelope,
    FaPhoneAlt,
    FaIdBadge,
    FaCamera,
    FaCheck,
    FaInfoCircle,
    FaUserCircle,
    FaSignOutAlt,
    FaBuilding,
} from "react-icons/fa";

import { developerAPI, imageSrc } from "../services/api";
import { useAuth } from "../context/AuthContext";
import WebcamCapture from "../components/common/WebcamCapture";
import logo from "../assets/faculty_sync_tracker_logo.png";

const ROLES = [
    { value: "FACULTY", label: "Faculty", desc: "Teaching & research faculty", color: "#2563eb", bg: "#eff6ff", icon: FaUserGraduate },
    { value: "HOD", label: "Head of Dept (HOD)", desc: "Department lead & approver", color: "#7c3aed", bg: "#f5f3ff", icon: FaUserShield },
    { value: "PRINCIPAL", label: "Principal", desc: "College head & approver", color: "#d97706", bg: "#fffbeb", icon: FaUserShield },
    { value: "VC", label: "Vice Chancellor", desc: "Executive leadership", color: "#dc2626", bg: "#fef2f2", icon: FaUserShield },
    { value: "TPO", label: "TPO", desc: "Training & placement officer", color: "#059669", bg: "#ecfdf5", icon: FaUserShield },
];

export default function CreateAccountPage() {
    const navigate = useNavigate();
    const { user, logout } = useAuth();

    const [formData, setFormData] = useState({
        firstName: "",
        lastName: "",
        email: "",
        phone: "",
        role: "FACULTY",
        departmentId: "",
        employeeId: "",
        leaveApproverId: "",
        secondaryApproverId: "",
    });

    const [departments, setDepartments] = useState([]);
    const [approvers, setApprovers] = useState([]);
    const [facultyList, setFacultyList] = useState([]);
    const [selectedFacultyIds, setSelectedFacultyIds] = useState([]);
    const [capturedPhoto, setCapturedPhoto] = useState(null);

    const [dataLoading, setDataLoading] = useState(true);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");

    // Load initial reference data
    useEffect(() => {
        let isMounted = true;

        const loadData = async () => {
            try {
                setDataLoading(true);
                const [deptRes, appRes, facRes] = await Promise.all([
                    developerAPI.getDepartments(),
                    developerAPI.getApprovers(),
                    developerAPI.getAccountsPage({
                        role: "FACULTY",
                        size: 500,
                        sort: "firstName",
                        direction: "asc",
                    }),
                ]);

                if (isMounted) {
                    setDepartments(Array.isArray(deptRes.data) ? deptRes.data : []);
                    setApprovers(Array.isArray(appRes.data) ? appRes.data : []);
                    setFacultyList(facRes.data?.content || []);
                }
            } catch (err) {
                console.error("Failed to load reference data:", err);
                if (isMounted) {
                    setError(err.response?.data?.message || "Failed to load departments or approvers.");
                }
            } finally {
                if (isMounted) {
                    setDataLoading(false);
                }
            }
        };

        loadData();
        return () => {
            isMounted = false;
        };
    }, []);

    // Field changes
    const handleChange = (e) => {
        const { name, value } = e.target;
        setFormData((prev) => ({ ...prev, [name]: value }));
        setSelectedFacultyIds([]);
        setError("");
        setSuccess("");
    };

    const handleRoleSelect = (roleValue) => {
        setFormData((prev) => ({
            ...prev,
            role: roleValue,
            leaveApproverId: "",
            secondaryApproverId: "",
        }));
        setSelectedFacultyIds([]);
        setError("");
        setSuccess("");
    };

    // Photo handlers
    const handlePhotoCapture = (blob) => {
        setCapturedPhoto(blob);
    };

    const handlePhotoClear = () => {
        setCapturedPhoto(null);
    };

    // Faculty toggle for HOD/Principal/TPO mappings
    const toggleFaculty = (facultyId) => {
        setSelectedFacultyIds((prev) =>
            prev.includes(facultyId)
                ? prev.filter((id) => id !== facultyId)
                : [...prev, facultyId]
        );
    };

    const departmentFaculty = facultyList.filter(
        (f) => f.role === "FACULTY" && String(f.departmentId) === String(formData.departmentId)
    );

    const toggleAllFaculty = () => {
        const deptFacultyIds = departmentFaculty.map((f) => f.id);
        const allSelected = deptFacultyIds.every((id) => selectedFacultyIds.includes(id));
        if (allSelected) {
            setSelectedFacultyIds((prev) => prev.filter((id) => !deptFacultyIds.includes(id)));
        } else {
            setSelectedFacultyIds((prev) => [...new Set([...prev, ...deptFacultyIds])]);
        }
    };

    // Form validation
    const validate = () => {
        if (!formData.firstName.trim()) return "First name is required.";
        if (!formData.lastName.trim()) return "Last name is required.";
        if (!formData.email.trim()) return "Email is required.";
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email.trim())) return "Enter a valid email address.";
        if (!formData.role) return "Please select an account role.";
        if (!formData.departmentId) return "Please select a department.";

        // Approver validation for hierarchy roles
        if (["FACULTY", "HOD", "TPO", "PRINCIPAL"].includes(formData.role)) {
            if (!formData.leaveApproverId) {
                return `Please assign a primary leave approver for ${formData.role}.`;
            }
        }

        // Mandatory photo capture for FACULTY
        if (formData.role === "FACULTY" && !capturedPhoto) {
            return "Profile photo is required. Please capture a photo using your camera or upload an approved image before creating the account.";
        }
        return "";
    };

    // Submission
    const handleSubmit = async (e) => {
        e.preventDefault();
        const validationError = validate();
        if (validationError) {
            setError(validationError);
            window.scrollTo({ top: 0, behavior: "smooth" });
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
                employeeId: formData.employeeId.trim() || undefined,
                leaveApproverId: formData.leaveApproverId ? Number(formData.leaveApproverId) : undefined,
                secondaryApproverId: formData.secondaryApproverId ? Number(formData.secondaryApproverId) : undefined,
                mappedFacultyIds: selectedFacultyIds.length > 0 ? selectedFacultyIds : undefined,
            };

            const res = await developerAPI.createAccount(payload, capturedPhoto);
            const msg = res.data?.message || "Account created successfully!";
            setSuccess(msg);

            // Redirect back to Developer Dashboard with notice state
            setTimeout(() => {
                navigate("/dev", { state: { notice: msg } });
            }, 1200);
        } catch (err) {
            console.error("Create account submission error:", err);
            setError(err.response?.data?.message || "Failed to create account. Please try again.");
            window.scrollTo({ top: 0, behavior: "smooth" });
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="cap-page">
            {/* TOP NAVIGATION BAR */}
            <header className="cap-topbar">
                <div className="cap-topbar-left" style={{ display: "flex", alignItems: "center", gap: "12px" }}>
                    <button
                        type="button"
                        className="cap-back-btn"
                        onClick={() => navigate("/dev")}
                        title="Back to Developer Dashboard"
                    >
                        <FaArrowLeft />
                        <span>Back to Dashboard</span>
                    </button>
                    <img
                        src={logo}
                        alt="Faculty Sync Tracker Logo"
                        style={{ height: "30px", width: "auto", objectFit: "contain" }}
                    />
                    <span className="cap-topbar-title">Faculty Sync Tracker — Developer Console</span>
                </div>

                <div className="cap-topbar-right">
                    <div className="cap-user-pill" title={user?.email || "Dev@college.edu"}>
                        <FaUserCircle />
                        <span>{user?.fullName || "System Developer"}</span>
                    </div>
                    <button
                        type="button"
                        className="cap-back-btn"
                        onClick={() => {
                            logout();
                            navigate("/login", { replace: true });
                        }}
                        title="Logout"
                    >
                        <FaSignOutAlt />
                        <span>Logout</span>
                    </button>
                </div>
            </header>

            {/* MAIN CONTENT CONTAINER */}
            <main className="cap-container">
                {/* HERO HEADER */}
                <section className="cap-hero-card">
                    <div className="cap-hero-content">
                        <h1>Create New Account</h1>
                        <p>
                            Configure a new faculty or administrative account, set up department assignment,
                            configure leave approval hierarchy, and issue password setup credentials.
                        </p>
                    </div>
                    <div className="cap-hero-badge">
                        <FaUserPlus />
                        <span>Account Provisioning</span>
                    </div>
                </section>

                {/* FORM CARD */}
                <div className="cap-card">
                    {/* ALERTS */}
                    {error && (
                        <div className="cap-alert cap-alert-error" role="alert">
                            <FaInfoCircle />
                            <span>{error}</span>
                        </div>
                    )}
                    {success && (
                        <div className="cap-alert cap-alert-success" role="status">
                            <FaCheck />
                            <span>{success}</span>
                        </div>
                    )}

                    {/* ACCOUNT TYPE / ROLE SELECTION */}
                    <div className="cap-section-header">
                        <div className="cap-section-icon">
                            <FaUserGraduate />
                        </div>
                        <h2>1. Select Account Type</h2>
                        <span>Choose the organizational role</span>
                    </div>

                    <div className="cap-roles-grid">
                        {ROLES.map((role) => {
                            const IconComponent = role.icon;
                            const isSelected = formData.role === role.value;
                            return (
                                <div
                                    key={role.value}
                                    className={`cap-role-option ${isSelected ? "cap-role-active" : ""}`}
                                    onClick={() => handleRoleSelect(role.value)}
                                    role="button"
                                    tabIndex={0}
                                    onKeyDown={(e) => {
                                        if (e.key === "Enter" || e.key === " ") handleRoleSelect(role.value);
                                    }}
                                >
                                    <div
                                        className="cap-role-icon"
                                        style={{ backgroundColor: role.bg, color: role.color }}
                                    >
                                        <IconComponent />
                                    </div>
                                    <span className="cap-role-name">{role.label}</span>
                                    <span className="cap-role-desc">{role.desc}</span>
                                </div>
                            );
                        })}
                    </div>

                    {/* FORM */}
                    <form onSubmit={handleSubmit} noValidate>
                        <div className="cap-section-header">
                            <div className="cap-section-icon">
                                <FaBuilding />
                            </div>
                            <h2>2. Account Details</h2>
                            <span>Personal and institutional information</span>
                        </div>

                        <div className="cap-form-grid">
                            {/* FIRST NAME */}
                            <div className="cap-form-group">
                                <label htmlFor="cap-firstName">
                                    First Name <span className="required">*</span>
                                </label>
                                <input
                                    id="cap-firstName"
                                    type="text"
                                    name="firstName"
                                    value={formData.firstName}
                                    onChange={handleChange}
                                    placeholder="Enter first name"
                                    disabled={loading || dataLoading}
                                    required
                                />
                            </div>

                            {/* LAST NAME */}
                            <div className="cap-form-group">
                                <label htmlFor="cap-lastName">
                                    Last Name <span className="required">*</span>
                                </label>
                                <input
                                    id="cap-lastName"
                                    type="text"
                                    name="lastName"
                                    value={formData.lastName}
                                    onChange={handleChange}
                                    placeholder="Enter last name"
                                    disabled={loading || dataLoading}
                                    required
                                />
                            </div>

                            {/* EMAIL */}
                            <div className="cap-form-group">
                                <label htmlFor="cap-email">
                                    Email Address <span className="required">*</span>
                                </label>
                                <div className="cap-input-wrap">
                                    <FaEnvelope className="cap-input-icon" />
                                    <input
                                        id="cap-email"
                                        type="email"
                                        name="email"
                                        value={formData.email}
                                        onChange={handleChange}
                                        placeholder="e.g. user@college.edu"
                                        disabled={loading || dataLoading}
                                        required
                                    />
                                </div>
                            </div>

                            {/* PHONE */}
                            <div className="cap-form-group">
                                <label htmlFor="cap-phone">Phone Number</label>
                                <div className="cap-input-wrap">
                                    <FaPhoneAlt className="cap-input-icon" />
                                    <input
                                        id="cap-phone"
                                        type="tel"
                                        name="phone"
                                        value={formData.phone}
                                        onChange={handleChange}
                                        placeholder="e.g. 9876543210"
                                        disabled={loading || dataLoading}
                                    />
                                </div>
                            </div>

                            {/* EMPLOYEE ID */}
                            <div className="cap-form-group">
                                <label htmlFor="cap-employeeId">Employee ID (Optional)</label>
                                <div className="cap-input-wrap">
                                    <FaIdBadge className="cap-input-icon" />
                                    <input
                                        id="cap-employeeId"
                                        type="text"
                                        name="employeeId"
                                        value={formData.employeeId}
                                        onChange={handleChange}
                                        placeholder="Leave blank to auto-generate"
                                        disabled={loading || dataLoading}
                                    />
                                </div>
                            </div>

                            {/* ROLE DROPDOWN */}
                            <div className="cap-form-group">
                                <label htmlFor="cap-role">
                                    Assigned Role <span className="required">*</span>
                                </label>
                                <select
                                    id="cap-role"
                                    name="role"
                                    value={formData.role}
                                    onChange={handleChange}
                                    disabled={loading || dataLoading}
                                >
                                    {ROLES.map((r) => (
                                        <option key={r.value} value={r.value}>
                                            {r.label}
                                        </option>
                                    ))}
                                </select>
                            </div>

                            {/* DEPARTMENT */}
                            <div className="cap-form-group cap-form-full">
                                <label htmlFor="cap-departmentId">
                                    Department <span className="required">*</span>
                                </label>
                                <select
                                    id="cap-departmentId"
                                    name="departmentId"
                                    value={formData.departmentId}
                                    onChange={handleChange}
                                    disabled={loading || dataLoading}
                                    required
                                >
                                    <option value="">-- Select Academic / Administrative Department --</option>
                                    {departments.map((dept) => (
                                        <option key={dept.id} value={dept.id}>
                                            {dept.name} ({dept.code})
                                        </option>
                                    ))}
                                </select>
                            </div>
                        </div>

                        {/* INFO BOX */}
                        <div className="cap-info-box">
                            <FaInfoCircle />
                            <div>
                                A secure account activation link will be automatically generated and dispatched to{" "}
                                <strong>{formData.email.trim() || "the specified email address"}</strong>. The account
                                holder will follow the link to set their initial password upon first login.
                            </div>
                        </div>

                        {/* WEBCAM PHOTO CAPTURE / FACE REGISTRATION */}
                        <div className="cap-section-header" style={{ marginTop: "28px" }}>
                            <div className="cap-section-icon" style={formData.role === "FACULTY" ? { color: "#2563eb", background: "#eff6ff" } : {}}>
                                <FaCamera />
                            </div>
                            <h2>
                                3. Face Registration
                                {formData.role === "FACULTY" ? (
                                    <span style={{ color: "#dc2626", fontSize: "0.82rem", marginLeft: "8px", fontWeight: "600" }}>
                                        (Mandatory for Faculty)
                                    </span>
                                ) : (
                                    <span style={{ color: "#6b7280", fontSize: "0.82rem", marginLeft: "8px", fontWeight: "normal" }}>
                                        (Optional)
                                    </span>
                                )}
                            </h2>
                            <span>
                                {formData.role === "FACULTY"
                                    ? "Camera capture or image upload required. This photo is registered as the profile photo and reference image for attendance verification."
                                    : "Facial capture or photo upload for profile identification (optional for administrative roles)"}
                            </span>
                        </div>

                        {formData.role === "FACULTY" && !capturedPhoto && (
                            <div style={{
                                maxWidth: "460px",
                                margin: "0 auto 12px",
                                padding: "10px 14px",
                                borderRadius: "8px",
                                background: "#fef2f2",
                                border: "1px solid #fecaca",
                                color: "#991b1b",
                                fontSize: "0.85rem",
                                display: "flex",
                                alignItems: "center",
                                gap: "8px"
                            }}>
                                <FaInfoCircle />
                                <span>Profile photo is required. Please capture or upload a photo to create a Faculty account.</span>
                            </div>
                        )}

                        {capturedPhoto && (
                            <div style={{
                                maxWidth: "460px",
                                margin: "0 auto 12px",
                                padding: "10px 14px",
                                borderRadius: "8px",
                                background: "#ecfdf5",
                                border: "1px solid #a7f3d0",
                                color: "#065f46",
                                fontSize: "0.85rem",
                                display: "flex",
                                alignItems: "center",
                                gap: "8px"
                            }}>
                                <FaCheck />
                                <span>Photo ready. Will be registered with the new account.</span>
                            </div>
                        )}

                        <div style={{ maxWidth: "460px", margin: "0 auto 28px" }}>
                            <WebcamCapture
                                onCapture={handlePhotoCapture}
                                onClear={handlePhotoClear}
                                disabled={loading || dataLoading}
                                allowUpload={true}
                            />
                        </div>

                        {/* ========================================================
                            DYNAMIC ROLE MAPPINGS & APPROVERS
                        ======================================================== */}

                        {/* FACULTY → HOD MAPPING */}
                        {formData.role === "FACULTY" && (
                            <div className="cap-mapping-box">
                                <div className="cap-mapping-title">
                                    <FaUserShield />
                                    <span>Faculty Approval Hierarchy</span>
                                </div>
                                <div className="cap-form-grid">
                                    <div className="cap-form-group">
                                        <label htmlFor="cap-leaveApproverId">
                                            Primary Approver (HOD / TPO) <span className="required">*</span>
                                        </label>
                                        <select
                                            id="cap-leaveApproverId"
                                            name="leaveApproverId"
                                            value={formData.leaveApproverId}
                                            onChange={handleChange}
                                            disabled={loading || dataLoading}
                                            required
                                        >
                                            <option value="">-- Select HOD or TPO --</option>
                                            {approvers
                                                .filter((a) => ["HOD", "TPO"].includes(a.role))
                                                .map((a) => (
                                                    <option key={a.id} value={a.id}>
                                                        {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role} -{" "}
                                                        {a.departmentName || "General"})
                                                    </option>
                                                ))}
                                        </select>
                                    </div>

                                    <div className="cap-form-group">
                                        <label htmlFor="cap-secondaryApproverId">Secondary Approver (Optional)</label>
                                        <select
                                            id="cap-secondaryApproverId"
                                            name="secondaryApproverId"
                                            value={formData.secondaryApproverId}
                                            onChange={handleChange}
                                            disabled={loading || dataLoading}
                                        >
                                            <option value="">-- Select Principal or VC --</option>
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
                            </div>
                        )}

                        {/* HOD → PRINCIPAL/VC MAPPING & FACULTY ASSIGNMENT */}
                        {formData.role === "HOD" && (
                            <div className="cap-mapping-box">
                                <div className="cap-mapping-title">
                                    <FaUserShield />
                                    <span>HOD Hierarchy & Department Faculty Mapping</span>
                                </div>
                                <div className="cap-form-grid">
                                    <div className="cap-form-group">
                                        <label htmlFor="cap-hodApproverId">
                                            Approves HOD Leave (Principal / VC) <span className="required">*</span>
                                        </label>
                                        <select
                                            id="cap-hodApproverId"
                                            name="leaveApproverId"
                                            value={formData.leaveApproverId}
                                            onChange={handleChange}
                                            disabled={loading || dataLoading}
                                            required
                                        >
                                            <option value="">-- Select Principal or VC --</option>
                                            {approvers
                                                .filter((a) => ["PRINCIPAL", "VC"].includes(a.role))
                                                .map((a) => (
                                                    <option key={a.id} value={a.id}>
                                                        {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                    </option>
                                                ))}
                                        </select>
                                    </div>

                                    <div className="cap-form-group">
                                        <label htmlFor="cap-hodSecondaryId">Secondary Approver (Optional)</label>
                                        <select
                                            id="cap-hodSecondaryId"
                                            name="secondaryApproverId"
                                            value={formData.secondaryApproverId}
                                            onChange={handleChange}
                                            disabled={loading || dataLoading}
                                        >
                                            <option value="">-- Select Secondary Approver --</option>
                                            {approvers
                                                .filter(
                                                    (a) =>
                                                        ["PRINCIPAL", "VC"].includes(a.role) &&
                                                        String(a.id) !== String(formData.leaveApproverId)
                                                )
                                                .map((a) => (
                                                    <option key={a.id} value={a.id}>
                                                        {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                    </option>
                                                ))}
                                        </select>
                                    </div>
                                </div>

                                {/* MAP DEPARTMENT FACULTY */}
                                {departmentFaculty.length > 0 && (
                                    <div className="cap-faculty-checklist">
                                        <div className="cap-checklist-top">
                                            <label className="cap-select-all-label">
                                                <input
                                                    type="checkbox"
                                                    checked={
                                                        departmentFaculty.length > 0 &&
                                                        departmentFaculty.every((f) => selectedFacultyIds.includes(f.id))
                                                    }
                                                    onChange={toggleAllFaculty}
                                                    disabled={loading}
                                                />
                                                <span>Map all faculty in selected department</span>
                                            </label>
                                            <span className="cap-selected-count">
                                                {selectedFacultyIds.length} of {departmentFaculty.length} selected
                                            </span>
                                        </div>

                                        <div className="cap-faculty-items">
                                            {departmentFaculty.map((f) => (
                                                <label key={f.id} className="cap-faculty-item">
                                                    <input
                                                        type="checkbox"
                                                        checked={selectedFacultyIds.includes(f.id)}
                                                        onChange={() => toggleFaculty(f.id)}
                                                        disabled={loading}
                                                    />
                                                    <div className="cap-faculty-avatar" style={{ overflow: "hidden" }}>
                                                        {f.profilePictureUrl ? (
                                                            <>
                                                                <img
                                                                    src={imageSrc(f.profilePictureUrl)}
                                                                    alt={f.fullName}
                                                                    style={{ width: "100%", height: "100%", objectFit: "cover" }}
                                                                    onError={(e) => {
                                                                        e.currentTarget.style.display = "none";
                                                                        if (e.currentTarget.nextSibling) e.currentTarget.nextSibling.style.display = "inline";
                                                                    }}
                                                                />
                                                                <span style={{ display: "none" }}>
                                                                    {f.fullName ? f.fullName.charAt(0).toUpperCase() : "F"}
                                                                </span>
                                                            </>
                                                        ) : (
                                                            <span>{f.fullName ? f.fullName.charAt(0).toUpperCase() : "F"}</span>
                                                        )}
                                                    </div>
                                                    <div className="cap-faculty-text">
                                                        <span className="cap-faculty-name">
                                                            {f.fullName || `${f.firstName} ${f.lastName}`}
                                                        </span>
                                                        <span className="cap-faculty-email">{f.email}</span>
                                                    </div>
                                                </label>
                                            ))}
                                        </div>
                                    </div>
                                )}
                            </div>
                        )}

                        {/* TPO → PRINCIPAL/VC MAPPING */}
                        {formData.role === "TPO" && (
                            <div className="cap-mapping-box">
                                <div className="cap-mapping-title">
                                    <FaUserShield />
                                    <span>TPO Approval Hierarchy</span>
                                </div>
                                <div className="cap-form-grid">
                                    <div className="cap-form-group">
                                        <label htmlFor="cap-tpoApproverId">
                                            Approves TPO Leave (Principal / VC) <span className="required">*</span>
                                        </label>
                                        <select
                                            id="cap-tpoApproverId"
                                            name="leaveApproverId"
                                            value={formData.leaveApproverId}
                                            onChange={handleChange}
                                            disabled={loading || dataLoading}
                                            required
                                        >
                                            <option value="">-- Select Principal or VC --</option>
                                            {approvers
                                                .filter((a) => ["PRINCIPAL", "VC"].includes(a.role))
                                                .map((a) => (
                                                    <option key={a.id} value={a.id}>
                                                        {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                    </option>
                                                ))}
                                        </select>
                                    </div>

                                    <div className="cap-form-group">
                                        <label htmlFor="cap-tpoSecondaryId">Secondary Approver (Optional)</label>
                                        <select
                                            id="cap-tpoSecondaryId"
                                            name="secondaryApproverId"
                                            value={formData.secondaryApproverId}
                                            onChange={handleChange}
                                            disabled={loading || dataLoading}
                                        >
                                            <option value="">-- Select Secondary Approver --</option>
                                            {approvers
                                                .filter(
                                                    (a) =>
                                                        ["PRINCIPAL", "VC"].includes(a.role) &&
                                                        String(a.id) !== String(formData.leaveApproverId)
                                                )
                                                .map((a) => (
                                                    <option key={a.id} value={a.id}>
                                                        {a.fullName || `${a.firstName} ${a.lastName}`} ({a.role})
                                                    </option>
                                                ))}
                                        </select>
                                    </div>
                                </div>
                            </div>
                        )}

                        {/* PRINCIPAL → VC MAPPING */}
                        {formData.role === "PRINCIPAL" && (
                            <div className="cap-mapping-box">
                                <div className="cap-mapping-title">
                                    <FaUserShield />
                                    <span>Principal Approval Hierarchy</span>
                                </div>
                                <div className="cap-form-grid">
                                    <div className="cap-form-group">
                                        <label htmlFor="cap-prnApproverId">
                                            Approves Principal Leave (VC) <span className="required">*</span>
                                        </label>
                                        <select
                                            id="cap-prnApproverId"
                                            name="leaveApproverId"
                                            value={formData.leaveApproverId}
                                            onChange={handleChange}
                                            disabled={loading || dataLoading}
                                            required
                                        >
                                            <option value="">-- Select Vice Chancellor --</option>
                                            {approvers
                                                .filter((a) => a.role === "VC")
                                                .map((a) => (
                                                    <option key={a.id} value={a.id}>
                                                        {a.fullName || `${a.firstName} ${a.lastName}`} (VC)
                                                    </option>
                                                ))}
                                        </select>
                                    </div>

                                    <div className="cap-form-group">
                                        <label htmlFor="cap-prnSecondaryId">Secondary Approver (Optional)</label>
                                        <select
                                            id="cap-prnSecondaryId"
                                            name="secondaryApproverId"
                                            value={formData.secondaryApproverId}
                                            onChange={handleChange}
                                            disabled={loading || dataLoading}
                                        >
                                            <option value="">-- Select Secondary Approver --</option>
                                            {approvers
                                                .filter(
                                                    (a) =>
                                                        a.role === "VC" &&
                                                        String(a.id) !== String(formData.leaveApproverId)
                                                )
                                                .map((a) => (
                                                    <option key={a.id} value={a.id}>
                                                        {a.fullName || `${a.firstName} ${a.lastName}`} (VC)
                                                    </option>
                                                ))}
                                        </select>
                                    </div>
                                </div>
                            </div>
                        )}

                        {/* FORM ACTION BUTTONS */}
                        <div className="cap-actions">
                            <button
                                type="button"
                                className="cap-btn-cancel"
                                onClick={() => navigate("/dev")}
                                disabled={loading}
                            >
                                Cancel
                            </button>
                            <button
                                type="submit"
                                className="cap-btn-submit"
                                disabled={loading || dataLoading || (formData.role === "FACULTY" && !capturedPhoto)}
                                title={formData.role === "FACULTY" && !capturedPhoto ? "Profile photo is required." : undefined}
                            >
                                {loading ? (
                                    <>
                                        <span className="cap-spinner" />
                                        <span>Creating Account...</span>
                                    </>
                                ) : (
                                    <>
                                        <FaUserPlus />
                                        <span>Create Account</span>
                                    </>
                                )}
                            </button>
                        </div>
                    </form>
                </div>
            </main>
        </div>
    );
}
