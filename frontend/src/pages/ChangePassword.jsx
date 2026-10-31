import "./ChangePassword.css";

import { useState } from "react";
import { useNavigate } from "react-router-dom";

import {
    FaLock,
    FaEye,
    FaEyeSlash,
    FaShieldAlt,
    FaCheckCircle,
    FaExclamationCircle,
    FaKey,
} from "react-icons/fa";

import { useAuth } from "../context/AuthContext";


function ChangePassword() {

    const navigate = useNavigate();
    const { user, completeFirstLogin } = useAuth();

    const [formData, setFormData] = useState({
        newPassword: "",
        confirmPassword: "",
    });

    const [showNewPassword, setShowNewPassword] = useState(false);
    const [showConfirmPassword, setShowConfirmPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");


    // ---- PASSWORD VALIDATION ----
    const validatePasswords = () => {
        if (!formData.newPassword) {
            return "Please enter a new password.";
        }
        if (formData.newPassword.length < 6) {
            return "Password must be at least 6 characters.";
        }
        if (!/[A-Z]/.test(formData.newPassword)) {
            return "Password must contain at least one uppercase letter.";
        }
        if (!/[a-z]/.test(formData.newPassword)) {
            return "Password must contain at least one lowercase letter.";
        }
        if (!/[0-9]/.test(formData.newPassword)) {
            return "Password must contain at least one number.";
        }
        if (formData.newPassword !== formData.confirmPassword) {
            return "Passwords do not match.";
        }
        return "";
    };


    // ---- PASSWORD STRENGTH ----
    const getPasswordStrength = (password) => {
        if (!password) return { score: 0, label: "", color: "" };

        let score = 0;
        if (password.length >= 6) score++;
        if (password.length >= 10) score++;
        if (/[A-Z]/.test(password)) score++;
        if (/[a-z]/.test(password)) score++;
        if (/[0-9]/.test(password)) score++;
        if (/[^A-Za-z0-9]/.test(password)) score++;

        if (score <= 2) return { score, label: "Weak", color: "#ef4444" };
        if (score <= 4) return { score, label: "Medium", color: "#f59e0b" };
        return { score, label: "Strong", color: "#10b981" };
    };


    // ---- SUBMIT ----
    const handleSubmit = async (e) => {
        e.preventDefault();
        setError("");
        setSuccess("");

        const validationError = validatePasswords();
        if (validationError) {
            setError(validationError);
            return;
        }

        try {
            setLoading(true);

            const result = await completeFirstLogin(
                formData.newPassword,
                formData.confirmPassword
            );

            if (result.success) {
                setSuccess("Password changed successfully! Redirecting...");

                // Redirect to dashboard based on role after 1.5 seconds
                setTimeout(() => {
                    const role = user?.role;
                    if (role === "FACULTY") {
                        navigate("/dashboard", { replace: true });
                    } else if (role === "DEVELOPER") {
                        navigate("/dev", { replace: true });
                    } else {
                        navigate("/admin", { replace: true });
                    }
                }, 1500);
            } else {
                setError(result.message || "Failed to change password.");
            }
        } catch (err) {
            console.error("Change password error:", err);
            setError("Something went wrong. Please try again.");
        } finally {
            setLoading(false);
        }
    };


    // ---- PASSWORD STRENGTH INDICATOR ----
    const strength = getPasswordStrength(formData.newPassword);


    // ---- RENDER ----
    return (
        <div className="cp-page">

            <div className="cp-card">

                {/* HEADER */}
                <div className="cp-header">
                    <div className="cp-icon-wrapper">
                        <FaShieldAlt className="cp-icon" />
                    </div>
                    <h1>{user?.firstLogin ? "First-Time Login" : "Change Password"}</h1>
                    <p>
                        {user?.firstLogin
                            ? <>Welcome, <strong>{user?.fullName || "User"}</strong>! Please change your password to continue.</>
                            : <>Update your account password for <strong>{user?.fullName || "User"}</strong>.</>}
                    </p>
                </div>


                {/* INFO BOX */}
                <div className="cp-info-box">
                    <FaKey className="cp-info-icon" />
                    <div>
                        <p><strong>Security Notice</strong></p>
                        <p>
                            You&apos;re logging in for the first time. For security reasons,
                            you must set a new password before accessing the system.
                        </p>
                    </div>
                </div>


                {/* MESSAGES */}
                {error && (
                    <div className="cp-alert cp-alert-error">
                        <FaExclamationCircle /><span>{error}</span>
                    </div>
                )}
                {success && (
                    <div className="cp-alert cp-alert-success">
                        <FaCheckCircle /><span>{success}</span>
                    </div>
                )}


                {/* FORM */}
                <form className="cp-form" onSubmit={handleSubmit}>

                    {/* NEW PASSWORD */}
                    <div className="cp-form-group">
                        <label>New Password</label>
                        <div className="cp-input-wrapper">
                            <FaLock className="cp-input-icon" />
                            <input
                                type={showNewPassword ? "text" : "password"}
                                value={formData.newPassword}
                                onChange={(e) => {
                                    setFormData({ ...formData, newPassword: e.target.value });
                                    setError("");
                                }}
                                placeholder="Enter new password"
                                disabled={loading}
                                autoComplete="new-password"
                            />
                            <button
                                type="button"
                                className="cp-toggle-btn"
                                onClick={() => setShowNewPassword(!showNewPassword)}
                                disabled={loading}
                            >
                                {showNewPassword ? <FaEyeSlash /> : <FaEye />}
                            </button>
                        </div>

                        {/* PASSWORD STRENGTH */}
                        {formData.newPassword && (
                            <div className="cp-strength">
                                <div className="cp-strength-bar">
                                    <div
                                        className="cp-strength-fill"
                                        style={{
                                            width: `${(strength.score / 6) * 100}%`,
                                            background: strength.color,
                                        }}
                                    />
                                </div>
                                <span style={{ color: strength.color }}>
                                    {strength.label}
                                </span>
                            </div>
                        )}
                    </div>


                    {/* CONFIRM PASSWORD */}
                    <div className="cp-form-group">
                        <label>Confirm Password</label>
                        <div className="cp-input-wrapper">
                            <FaLock className="cp-input-icon" />
                            <input
                                type={showConfirmPassword ? "text" : "password"}
                                value={formData.confirmPassword}
                                onChange={(e) => {
                                    setFormData({ ...formData, confirmPassword: e.target.value });
                                    setError("");
                                }}
                                placeholder="Confirm new password"
                                disabled={loading}
                                autoComplete="new-password"
                            />
                            <button
                                type="button"
                                className="cp-toggle-btn"
                                onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                                disabled={loading}
                            >
                                {showConfirmPassword ? <FaEyeSlash /> : <FaEye />}
                            </button>
                        </div>

                        {/* MATCH INDICATOR */}
                        {formData.confirmPassword && formData.newPassword && (
                            <div className={`cp-match ${
                                formData.newPassword === formData.confirmPassword
                                    ? "cp-match-valid"
                                    : "cp-match-invalid"
                            }`}>
                                {formData.newPassword === formData.confirmPassword ? (
                                    <>
                                        <FaCheckCircle />
                                        <span>Passwords match</span>
                                    </>
                                ) : (
                                    <>
                                        <FaExclamationCircle />
                                        <span>Passwords do not match</span>
                                    </>
                                )}
                            </div>
                        )}
                    </div>


                    {/* PASSWORD REQUIREMENTS */}
                    <div className="cp-requirements">
                        <p>Password must contain:</p>
                        <ul>
                            <li className={formData.newPassword.length >= 6 ? "cp-req-met" : ""}>
                                At least 6 characters
                            </li>
                            <li className={/[A-Z]/.test(formData.newPassword) ? "cp-req-met" : ""}>
                                One uppercase letter
                            </li>
                            <li className={/[a-z]/.test(formData.newPassword) ? "cp-req-met" : ""}>
                                One lowercase letter
                            </li>
                            <li className={/[0-9]/.test(formData.newPassword) ? "cp-req-met" : ""}>
                                One number
                            </li>
                        </ul>
                    </div>


                    {/* SUBMIT */}
                    <button
                        type="submit"
                        className="cp-submit-btn"
                        disabled={loading}
                    >
                        {loading ? (
                            <>
                                <span className="cp-spinner"></span>
                                Changing Password...
                            </>
                        ) : (
                            <>
                                <FaCheckCircle />
                                Change Password & Continue
                            </>
                        )}
                    </button>

                </form>

            </div>

        </div>
    );
}

export default ChangePassword;
