import { useEffect, useRef, useState } from "react";

import {
    FaEnvelope,
    FaPhoneAlt,
    FaHome,
    FaPencilAlt,
    FaSave,
    FaTimes,
    FaCamera,
    FaIdBadge,
    FaBuilding,
    FaShieldAlt,
    FaSignOutAlt
} from "react-icons/fa";

import { useNavigate } from "react-router-dom";
import { profileAPI, imageSrc } from "../../services/api";
import { useAuth } from "../../context/AuthContext";


function ProfileTab() {

    const navigate = useNavigate();
    const { user, updateUser, logout, isAdmin: checkAdmin, isDeveloper: checkDeveloper } = useAuth();
    const isAdmin = checkAdmin();
    const isDeveloper = checkDeveloper();
    const fileInputRef = useRef(null);

    const [profile, setProfile] = useState(null);
    const [editing, setEditing] = useState(false);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [uploading, setUploading] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");
    const [imgError, setImgError] = useState(false);

    const [formData, setFormData] = useState({
        phone: "",
        address: "",
        bio: ""
    });


    // =====================================================
    // LOAD PROFILE
    // =====================================================

    useEffect(() => {

        const loadProfile = async () => {

            try {
                setLoading(true);

                // Try to load from API
                const res = await profileAPI.getProfile();
                setProfile(res.data);
                setImgError(false);

                setFormData({
                    phone: res.data.phone || "",
                    address: res.data.address || "",
                    bio: res.data.bio || ""
                });

            } catch (err) {

                console.error("Profile load error:", err);

                // Fallback to auth context data
                if (user) {

                    const fallbackProfile = {
                        id: user.userId,
                        firstName: user.fullName?.split(" ")[0] || "",
                        lastName: user.fullName?.split(" ").slice(1).join(" ") || "",
                        email: user.email,
                        role: user.role,
                        departmentName: user.departmentName,
                        phone: "",
                        address: "",
                        bio: "",
                        profilePictureUrl: user.profilePictureUrl,
                        employeeId: "N/A"
                    };

                    setProfile(fallbackProfile);

                    setFormData({
                        phone: "",
                        address: "",
                        bio: ""
                    });

                } else {

                    setError("Failed to load profile.");

                }

            } finally {
                setLoading(false);
            }

        };

        loadProfile();

    }, [user]);


    // =====================================================
    // SAVE PROFILE
    // =====================================================

    const handleSave = async () => {

        try {
            setSaving(true);
            setError("");
            const res = await profileAPI.updateProfile(formData);
            setProfile((prev) => ({ ...prev, ...res.data }));
            setEditing(false);
            setSuccess("Profile updated successfully!");
            setTimeout(() => setSuccess(""), 3000);
        } catch (err) {
            console.error("Profile save error:", err);
            setError(err.response?.data?.message || "Failed to update profile.");
        } finally {
            setSaving(false);
        }

    };


    // =====================================================
    // CANCEL EDIT
    // =====================================================

    const handleCancel = () => {

        setFormData({
            phone: profile?.phone || "",
            address: profile?.address || "",
            bio: profile?.bio || ""
        });
        setEditing(false);
        setError("");

    };


    // =====================================================
    // UPLOAD PICTURE
    // =====================================================

    const handlePictureUpload = async (e) => {

        const file = e.target.files[0];
        if (!file) return;

        // Client-side validation mirrors the backend rules (JPEG/PNG/WebP, 5 MB).
        // The backend re-validates real content, so this can never be bypassed.
        const allowedTypes = ["image/jpeg", "image/png", "image/webp"];
        if (!allowedTypes.includes(file.type)) {
            setError("Please upload a JPEG, PNG or WebP image.");
            return;
        }
        if (file.size === 0) {
            setError("Please choose a valid image file.");
            return;
        }
        if (file.size > 5 * 1024 * 1024) {
            setError("File size must be less than 5MB.");
            return;
        }

        try {
            setUploading(true);
            setError("");
            const res = await profileAPI.uploadPicture(file);
            setProfile((prev) => ({ ...prev, ...res.data }));
            setImgError(false);
            // Persist into the auth cache so the header/avatar elsewhere uses
            // the new image immediately (login state survives reload).
            updateUser({ profilePictureUrl: res.data.profilePictureUrl });
            setSuccess("Profile picture updated!");
            setTimeout(() => setSuccess(""), 3000);
        } catch (err) {
            console.error("Picture upload error:", err);
            setError(err.response?.data?.message || "Failed to upload picture. Please try again.");
        } finally {
            setUploading(false);
        }

    };


    // =====================================================
    // READONLY FIELDS
    // =====================================================

    const formatRole = (role) => {
        if (!role) return "N/A";
        const roleMap = {
            DEVELOPER: "System Developer",
            FACULTY: "Assistant Professor",
            HOD: "Head of Department",
            VC: "Vice Chancellor",
            PRINCIPAL: "Principal",
            TPO: "Training & Placement Officer"
        };
        return roleMap[role] || role.charAt(0) + role.slice(1).toLowerCase().replace(/_/g, " ");
    };

    const readOnlyFields = [
        { label: "Email", value: profile?.email, icon: FaEnvelope },
        ...(!isDeveloper ? [
            { label: "Employee ID", value: profile?.employeeId, icon: FaIdBadge },
            { label: "Department", value: profile?.departmentName, icon: FaBuilding },
            { label: "Role", value: formatRole(profile?.role), icon: FaShieldAlt }
        ] : [
            { label: "Employee ID", value: profile?.employeeId, icon: FaIdBadge },
            { label: "Role", value: formatRole(profile?.role), icon: FaShieldAlt }
        ])
    ];


    // =====================================================
    // LOADING STATE
    // =====================================================

    if (loading) {
        return (
            <div className="fd-profile-loading">
                <div className="fd-inbox-spinner"></div>
                <span>Loading profile...</span>
            </div>
        );
    }


    // =====================================================
    // RENDER
    // =====================================================

    return (

        <div className="fd-profile-tab">


            {/* =================================================
                TOP ROW - PICTURE LEFT, LOGOUT RIGHT
            ================================================= */}

            <div className="fd-profile-top-row">

                {/* PROFILE PICTURE */}
                <div className="fd-profile-picture-wrapper">
                    <div className="fd-profile-picture fd-profile-picture-lg">
                        {profile?.profilePictureUrl && !imgError ? (
                            <img
                                src={imageSrc(profile.profilePictureUrl)}
                                onError={() => setImgError(true)}
                                alt="Profile"
                            />
                        ) : (
                            <span>{profile?.firstName?.charAt(0) || "U"}</span>
                        )}
                    </div>
                    <button type="button" className="fd-profile-camera-btn"
                        onClick={() => fileInputRef.current?.click()}
                        disabled={uploading}>
                        <FaCamera />
                    </button>
                    <input ref={fileInputRef} type="file" accept="image/*"
                        onChange={handlePictureUpload} className="fd-hidden-input" />
                    {uploading && <div className="fd-inbox-spinner fd-profile-upload-spinner"></div>}
                </div>

                {/* LOGOUT BUTTON */}
                <button
                    type="button"
                    className="fd-profile-logout-btn"
                    onClick={() => {
                        logout();
                        navigate("/login", { replace: true });
                    }}
                >
                    <FaSignOutAlt />
                    <span>Logout</span>
                </button>

            </div>


            {/* =================================================
                BACK BUTTON (ADMIN & DEVELOPER)
            ================================================= */}

            {(isAdmin || isDeveloper) && (
                <div className="fd-profile-back-section">
                    <button
                        type="button"
                        className="fd-profile-back-btn"
                        onClick={() => navigate(isDeveloper ? "/dev" : "/admin")}
                    >
                        ← Back to Dashboard
                    </button>
                </div>
            )}


            {/* =================================================
                SUCCESS / ERROR MESSAGES
            ================================================= */}

            {success && (
                <div className="fd-profile-success">{success}</div>
            )}

            {error && (
                <div className="fd-profile-error">{error}</div>
            )}


            {/* =================================================
                NAME + EMAIL + BADGES
            ================================================= */}

            <div className="fd-profile-card fd-profile-header-card">
                <div className="fd-profile-name-section">
                    <h2>{profile?.firstName} {profile?.lastName}</h2>
                    <p>{profile?.email}</p>
                    <div className="fd-profile-badges">
                        <span className="fd-profile-badge fd-profile-badge-role">{formatRole(profile?.role)}</span>
                        {!isDeveloper && profile?.departmentName && (
                            <span className="fd-profile-badge fd-profile-badge-dept">{profile?.departmentName}</span>
                        )}
                    </div>
                </div>
            </div>


            {/* =================================================
                OFFICIAL INFORMATION (READ ONLY)
            ================================================= */}

            <div className="fd-profile-card">
                <h3 className="fd-profile-section-title">Official Information</h3>
                <div className="fd-profile-fields-grid">
                    {readOnlyFields.map((field) => (
                        <div
                            className={`fd-profile-field fd-profile-field-${field.label.toLowerCase().replace(/\s+/g, '-')}`}
                            key={field.label}
                        >
                            <field.icon className="fd-profile-field-icon" />
                            <div>
                                <span className="fd-profile-field-label">{field.label}</span>
                                <span className="fd-profile-field-value">{field.value || "N/A"}</span>
                            </div>
                        </div>
                    ))}
                </div>
                <p className="fd-profile-readonly-note">These fields are read-only. Contact admin to update.</p>
            </div>


            {/* =================================================
                PERSONAL DETAILS (EDITABLE)
            ================================================= */}

            <div className="fd-profile-card">
                <div className="fd-profile-section-header">
                    <h3 className="fd-profile-section-title">Personal Details</h3>
                    {!editing ? (
                        <button type="button" className="fd-profile-edit-btn" onClick={() => setEditing(true)}>
                            <FaPencilAlt /> Edit
                        </button>
                    ) : (
                        <div className="fd-profile-edit-actions">
                            <button type="button" className="fd-profile-cancel-btn" onClick={handleCancel}>
                                <FaTimes /> Cancel
                            </button>
                            <button type="button" className="fd-profile-save-btn" onClick={handleSave} disabled={saving}>
                                {saving ? <div className="fd-inbox-spinner fd-btn-spinner"></div> : <FaSave />}
                                Save
                            </button>
                        </div>
                    )}
                </div>

                <div className="fd-profile-form">


                    <div className="fd-profile-form-group">
                        <label>Phone Number</label>
                        <div className="fd-profile-input-wrapper">
                            <FaPhoneAlt className="fd-profile-input-icon" />
                            <input type="tel" value={formData.phone}
                                onChange={(e) => setFormData({ ...formData, phone: e.target.value })}
                                disabled={!editing} placeholder="Enter phone number" />
                        </div>
                    </div>


                    <div className="fd-profile-form-group">
                        <label>Address</label>
                        <div className="fd-profile-input-wrapper">
                            <FaHome className="fd-profile-input-icon fd-profile-textarea-icon" />
                            <textarea value={formData.address}
                                onChange={(e) => setFormData({ ...formData, address: e.target.value })}
                                disabled={!editing} rows={2} placeholder="Enter address" />
                        </div>
                    </div>


                    <div className="fd-profile-form-group">
                        <label>Bio</label>
                        <textarea value={formData.bio}
                            onChange={(e) => setFormData({ ...formData, bio: e.target.value })}
                            disabled={!editing} rows={3} placeholder="Tell us about yourself..."
                            className="fd-profile-textarea-full" />
                    </div>

                </div>

            </div>

        </div>

    );

}

export default ProfileTab;
