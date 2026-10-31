import { useState, useEffect } from "react";
import { useSearchParams, useNavigate } from "react-router-dom";

import {
    FaLock,
    FaEye,
    FaEyeSlash,
    FaShieldAlt,
    FaCheckCircle,
    FaExclamationCircle,
    FaKey,
    FaSpinner,
} from "react-icons/fa";

import { authAPI } from "../services/api";


function SetPasswordPage() {

    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const token = searchParams.get("token");

    const [formData, setFormData] = useState({
        newPassword: "",
        confirmPassword: "",
    });

    const [showNewPassword, setShowNewPassword] = useState(false);
    const [showConfirmPassword, setShowConfirmPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [validating, setValidating] = useState(true);
    const [tokenValid, setTokenValid] = useState(false);
    const [userInfo, setUserInfo] = useState(null);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");


    // =====================================================
    // VALIDATE TOKEN ON MOUNT
    // =====================================================

    useEffect(() => {

        if (!token) {
            setValidating(false);
            setError("No setup link found. Please check your email for the correct link.");
            return;
        }

        const validateToken = async () => {
            try {
                const res = await authAPI.validateSetupToken(token);
                const data = res.data;

                if (data.valid) {
                    setTokenValid(true);
                    setUserInfo(data);
                } else if (data.reason === "EXPIRED") {
                    setError("Your activation link has expired. Please request a new activation link from your administrator.");
                } else {
                    setError(
                        "This activation link has already been used or is invalid. " +
                        "If your account is already active, log in with your password; " +
                        "otherwise request a new activation link."
                    );
                }
            } catch (err) {
                console.error("Token validation error:", err);
                setError("This setup link is invalid or has expired. Please contact the administrator.");
            } finally {
                setValidating(false);
            }
        };

        validateToken();
    }, [token]);


    // =====================================================
    // PASSWORD VALIDATION
    // =====================================================

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


    // =====================================================
    // PASSWORD STRENGTH
    // =====================================================

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


    // =====================================================
    // SUBMIT
    // =====================================================

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

            const result = await authAPI.setupPassword({
                token,
                newPassword: formData.newPassword,
                confirmPassword: formData.confirmPassword,
            });

            if (result.data?.message) {
                setSuccess("Password set successfully! Redirecting to login...");

                setTimeout(() => {
                    navigate("/login", {
                        replace: true,
                        state: { activationSuccess: true },
                    });
                }, 2000);
            }
        } catch (err) {
            console.error("Setup password error:", err);
            setError(
                err.response?.data?.message ||
                "Failed to set password. Please try again."
            );
        } finally {
            setLoading(false);
        }
    };


    // =====================================================
    // PASSWORD STRENGTH INDICATOR
    // =====================================================

    const strength = getPasswordStrength(formData.newPassword);


    // =====================================================
    // LOADING STATE
    // =====================================================

    if (validating) {
        return (
            <div className="cp-page">
                <div className="cp-card" style={{ textAlign: "center", padding: "60px 40px" }}>
                    <FaSpinner className="cp-icon" style={{ animation: "spin 1s linear infinite", margin: "0 auto 20px" }} />
                    <h2 style={{ marginBottom: "10px" }}>Verifying your setup link...</h2>
                    <p style={{ color: "#666" }}>Please wait while we validate your link.</p>
                </div>
            </div>
        );
    }


    // =====================================================
    // INVALID TOKEN STATE
    // =====================================================

    if (!tokenValid) {
        return (
            <div className="cp-page">
                <div className="cp-card">
                    <div className="cp-header">
                        <div className="cp-icon-wrapper" style={{ background: "#fef2f2" }}>
                            <FaExclamationCircle className="cp-icon" style={{ color: "#ef4444" }} />
                        </div>
                        <h1>Link Invalid</h1>
                        <p style={{ color: "#666" }}>
                            {error || "This setup link is invalid or has expired."}
                        </p>
                    </div>

                    <button
                        type="button"
                        className="cp-submit-btn"
                        onClick={() => navigate("/login", { replace: true })}
                        style={{ marginTop: "20px" }}
                    >
                        Go to Login
                    </button>
                </div>
            </div>
        );
    }


    // =====================================================
    // RENDER - SET PASSWORD FORM
    // =====================================================

    return (

        <div className="cp-page">

            <div className="cp-card">

                {/* HEADER */}
                <div className="cp-header">
                    <div className="cp-icon-wrapper">
                        <FaShieldAlt className="cp-icon" />
                    </div>
                    <h1>Set Your Password</h1>
                    <p>
                        Welcome, <strong>{userInfo?.fullName || "User"}</strong>!
                        Please create a password for your account.
                    </p>
                </div>


                {/* ACCOUNT INFO */}
                <div className="cp-info-box">
                    <FaKey className="cp-info-icon" />
                    <div>
                        <p><strong>Your Account</strong></p>
                        <p>
                            Email: <strong>{userInfo?.email}</strong><br />
                            Employee ID: <strong>{userInfo?.employeeId}</strong><br />
                            Role: <strong>{userInfo?.role}</strong>
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
                                placeholder="Enter your password"
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
                                placeholder="Confirm your password"
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
                                Setting Password...
                            </>
                        ) : (
                            <>
                                <FaCheckCircle />
                                Set Password & Login
                            </>
                        )}
                    </button>

                </form>

            </div>

        </div>

    );
}

export default SetPasswordPage;
