import "./Login.css";
import logo from "../assets/faculty_sync_tracker_logo.png";

import { useState } from "react";
import { Link } from "react-router-dom";

import { authAPI } from "../services/api";

import { MdEmail } from "react-icons/md";
import { FaCheckCircle, FaExclamationCircle } from "react-icons/fa";

/**
 * Forgot Password page.
 *
 * Uses Login.css exclusively as the visual source of truth — same background,
 * card, input, icon, spinner and button styling as the existing Login page.
 *
 * Behavior follows the backend contract: the response is intentionally
 * generic whether or not the account exists, so the page shows the same
 * success message and never reveals account existence.
 */
function ForgotPasswordPage() {

    // =====================================================
    // STATE
    // =====================================================

    const [email, setEmail] = useState("");
    const [error, setError] = useState("");
    const [notice, setNotice] = useState("");
    const [submitted, setSubmitted] = useState(false);
    const [loading, setLoading] = useState(false);


    // =====================================================
    // EMAIL VALIDATION (same rules as Login)
    // =====================================================

    const validateEmail = (value) => {

        const trimmedValue = value.trim();

        if (!trimmedValue) {
            return "Please enter your Email ID";
        }

        if (/\s/.test(value)) {
            return "Email ID must not contain spaces";
        }

        const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

        if (!emailPattern.test(trimmedValue)) {
            return "Please enter a valid Email ID";
        }

        return "";
    };


    // =====================================================
    // CLEAR ERROR
    // =====================================================

    const clearError = () => {
        if (error) {
            setError("");
        }
    };


    // =====================================================
    // SUBMIT
    // =====================================================

    const handleSubmit = async (event) => {

        event.preventDefault();

        // Prevent duplicate requests
        if (loading || submitted) {
            return;
        }

        setNotice("");

        const emailError = validateEmail(email);
        if (emailError) {
            setError(emailError);
            return;
        }

        try {

            setLoading(true);

            // Backend returns a generic message for known and unknown
            // emails (no account enumeration) and a 429 when throttled.
            const response =
                await authAPI.forgotPassword(
                    email.trim()
                );

            setNotice(
                response?.data?.message ||
                "If an account exists for that email address, a password reset link has been sent."
            );

            setSubmitted(true);

        } catch (requestError) {

            if (
                requestError.response &&
                requestError.response.status === 429
            ) {

                setError(
                    requestError.response?.data?.message ||
                    "Too many password reset requests. Please try again later."
                );

                return;
            }

            if (
                requestError.response &&
                requestError.response.status === 400
            ) {

                setError(
                    requestError.response?.data?.message ||
                    "Please enter a valid email address."
                );

                return;
            }

            if (requestError.request) {
                setError(
                    "Unable to connect to the server. Please check your connection."
                );

                return;
            }

            setError(
                "Something went wrong. Please try again."
            );

        } finally {
            setLoading(false);
        }
    };


    // =====================================================
    // EMAIL CHANGE
    // =====================================================

    const handleEmailChange = (event) => {
        setEmail(event.target.value);
        clearError();
    };


    // =====================================================
    // JSX
    // =====================================================

    return (

        <div className="login-container">

            <form
                className="form"
                onSubmit={handleSubmit}
                noValidate
            >

                {/* ==============================================
                    LOGO / TITLE — same branding as Login
                ============================================== */}

                <div className="logo">

                    <img
                        src={logo}
                        alt="Faculty Sync Tracker Logo"
                    />

                    <div className="title">
                        <span
                            className="live-dot"
                            aria-hidden="true"
                        />
                        <h2>
                            Reset Password
                        </h2>

                    </div>

                </div>

                {submitted ? (

                    /* ==============================================
                        SUCCESS STATE
                    ============================================== */

                    <>
                        <div
                            className="inputForm"
                            role="status"
                            aria-live="polite"
                            style={{
                                alignItems: "flex-start"
                            }}
                        >

                            <FaCheckCircle
                                className="icon"
                                aria-hidden="true"
                            />

                            <p
                                className="input"
                                style={{
                                    border: "none",
                                    color: "#16a34a",
                                    fontSize: "14px",
                                    lineHeight: 1.5,
                                    margin: 0,
                                    padding: 0
                                }}
                            >

                                {notice}

                            </p>

                        </div>

                        <div className="wrapper">

                            <Link
                                to="/login"
                                className="button-submit"
                                style={{
                                    display: "block",
                                    textAlign: "center",
                                    textDecoration: "none"
                                }}
                            >

                                <span>
                                    Back to Login
                                </span>

                            </Link>

                        </div>
                    </>
                ) : (

                    /* ==============================================
                        EMAIL FORM
                    ============================================== */

                    <>

                        <p
                            style={{
                                color: "#555",
                                fontSize: "14px",
                                lineHeight: 1.5,
                                margin: "0 0 24px",
                                padding: 0
                            }}
                        >

                            Enter the email address linked to your account
                            and we will send you a secure, single-use link
                            to reset your password.

                        </p>

                        {/* ==========================================
                            EMAIL — same input design as Login
                        ========================================== */}

                        <div className="inputForm">

                            <MdEmail
                                className="icon"
                                aria-hidden="true"
                            />

                            <input
                                type="email"
                                className="input"
                                placeholder="Email ID"
                                value={email}
                                onChange={
                                    handleEmailChange
                                }
                                autoComplete="username"
                                disabled={loading}
                                aria-label="Email ID"
                                aria-required="true"
                                required
                            />

                        </div>

                        {/* ==========================================
                            ERROR MESSAGE — same style as Login
                        ========================================== */}

                        {notice && (
                            <p
                                className="login-error"
                                style={{ color: "#16a34a" }}
                                role="status"
                                aria-live="polite"
                            >
                                {notice}
                            </p>
                        )}

                        {error && (
                            <p
                                className="login-error"
                                role="alert"
                                aria-live="polite"
                            >

                                {error.startsWith("Too many") ||
                                 error.startsWith("Unable") ||
                                 error.startsWith("Something") ? (

                                    <FaExclamationCircle
                                        aria-hidden="true"
                                    />
                                ) : null}

                                {" "}
                                {error}

                            </p>
                        )}

                        {/* ==========================================
                            SUBMIT BUTTON — same button design
                        ========================================== */}

                        <div className="wrapper">

                            <button
                                type="submit"
                                className="button-submit"
                                disabled={loading}
                                aria-busy={loading}
                            >

                                {loading ? (

                                    <span className="loading-container">

                                        <span
                                            className="loading-spinner"
                                            aria-hidden="true"
                                        />

                                        <span>
                                            Sending...
                                        </span>

                                    </span>

                                ) : (

                                    <span>
                                        SEND RESET LINK
                                    </span>

                                )}
                            </button>

                        </div>

                        {/* ==========================================
                            BACK TO LOGIN — same link style
                        ========================================== */}

                        <div className="flex-row"
                            style={{
                                justifyContent: "center"
                            }}
                        >

                            <Link
                                to="/login"
                                className="forgot"
                            >
                                Back to Login
                            </Link>

                        </div>

                    </>
                )}

                {/* ==============================================
                    FOOTER — same as Login
                ============================================== */}

                <p className="footer">
                    © 2026 Faculty Sync Tracker
                </p>

            </form>

        </div>
    );
}

export default ForgotPasswordPage;
