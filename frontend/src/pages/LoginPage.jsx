import "./Login.css";
import logo from "../assets/faculty_sync_tracker_logo.png";

import { useState } from "react";
import { Link, useNavigate, useLocation } from "react-router-dom";

import { useAuth } from "../context/AuthContext";

import { MdEmail } from "react-icons/md";
import {
    FaLock,
    FaEye,
    FaEyeSlash
} from "react-icons/fa";


function LoginPage() {

    const navigate = useNavigate();
    const location = useLocation();

    const { login } = useAuth();


    // =====================================================
    // CONSTANTS
    // =====================================================

    const REMEMBERED_EMAIL_KEY =
        "rememberedEmail";


    // =====================================================
    // STATE
    // =====================================================

    const [
        email,
        setEmail
    ] = useState(
        () =>
            localStorage.getItem(
                REMEMBERED_EMAIL_KEY
            ) || ""
    );


    const [
        password,
        setPassword
    ] = useState("");


    const [
        showPassword,
        setShowPassword
    ] = useState(false);


    const [
        rememberMe,
        setRememberMe
    ] = useState(
        () =>
            Boolean(
                localStorage.getItem(
                    REMEMBERED_EMAIL_KEY
                )
            )
    );


    const [
        error,
        setError
    ] = useState("");

    // Success banner after a first-time password setup (§7)
    const [activationNotice] = useState(
        location.state?.activationSuccess
            ? "Your account has been activated successfully. You can now log in using your email and password."
            : ""
    );

    const [
        loading,
        setLoading
    ] = useState(false);


    // =====================================================
    // EMAIL VALIDATION
    // =====================================================

    const validateEmail = (value) => {

        const trimmedValue =
            value.trim();

        if (!trimmedValue) {

            return "Please enter your Email ID";
        }

        if (/\s/.test(value)) {

            return "Email ID must not contain spaces";
        }

        const emailPattern =
            /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

        if (
            !emailPattern.test(
                trimmedValue
            )
        ) {
            return "Please enter a valid Email ID";
        }

        return "";
    };


    // =====================================================
    // PASSWORD VALIDATION
    // =====================================================

    const validatePassword = (value) => {

        if (!value) {

            return "Please enter your Password";
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
    // SAVE REMEMBERED EMAIL
    // =====================================================

    const handleRememberedEmail = (
        emailValue
    ) => {

        if (rememberMe) {
            localStorage.setItem(
                REMEMBERED_EMAIL_KEY,
                emailValue
            );
        } else {
            localStorage.removeItem(
                REMEMBERED_EMAIL_KEY
            );
        }
    };


    // =====================================================
    // NAVIGATE USER BASED ON ROLE
    // =====================================================

    const navigateByRole = (data) => {

        const role =
            data?.role;


        switch (role) {


            // ---------------------------------------------
            // FACULTY
            // ---------------------------------------------

            case "FACULTY":

                navigate(
                    "/dashboard",
                    {
                        replace: true
                    }
                );

                return;


            // ---------------------------------------------
            // DEVELOPER ROLE
            // ---------------------------------------------

            case "DEVELOPER":

                navigate(
                    "/dev",
                    {
                        replace: true
                    }
                );

                return;


            // ---------------------------------------------
            // ADMIN ROLES (HOD, VC, PRINCIPAL, TPO)
            // ---------------------------------------------

            case "HOD":
            case "VC":
            case "PRINCIPAL":
            case "TPO":

                navigate(
                    "/admin",
                    {
                        replace: true
                    }
                );

                return;


            // ---------------------------------------------
            // UNKNOWN ROLE
            // ---------------------------------------------

            default:

                setError(
                    "Your account has an invalid role. Please contact the administrator."
                );
        }
    };


    // =====================================================
    // LOGIN
    // =====================================================

    const handleLogin = async (
        event
    ) => {

        event.preventDefault();


        // -------------------------------------------------
        // PREVENT DUPLICATE REQUESTS
        // -------------------------------------------------

        if (loading) {

            return;
        }

        setError("");


        // -------------------------------------------------
        // PREPARE VALUES
        // -------------------------------------------------

        const trimmedEmail =
            email.trim();


        // -------------------------------------------------
        // VALIDATE EMAIL
        // -------------------------------------------------

        const emailError =
            validateEmail(
                email
            );

        if (emailError) {

            setError(
                emailError
            );

            return;
        }


        // -------------------------------------------------
        // VALIDATE PASSWORD
        // -------------------------------------------------

        const passwordError =
            validatePassword(
                password
            );

        if (passwordError) {

            setError(
                passwordError
            );

            return;
        }


        try {

            setLoading(true);


            // =================================================
            // LOGIN API (via AuthContext)
            // =================================================

            const result =
                await login(
                    trimmedEmail,
                    password
                );


            // =================================================
            // LOGIN FAILURE
            // =================================================

            if (
                !result ||
                result.success !== true
            ) {

                setError(
                    result?.message ||
                    "Invalid Email ID or Password"
                );

                return;
            }


            // =================================================
            // REMEMBER ME
            // =================================================

            handleRememberedEmail(
                trimmedEmail
            );


            // =================================================
            // FIRST LOGIN CHECK
            // =================================================

            if (
                result.firstLogin &&
                result.user?.role !== "DEVELOPER"
            ) {
                navigate(
                    "/change-password",
                    {
                        replace: true
                    }
                );
                return;
            }


            // =================================================
            // ROLE-BASED NAVIGATION
            // =================================================

            navigateByRole(
                result.user
            );

        }

        // =====================================================
        // HTTP / NETWORK ERROR
        // =====================================================

        catch (requestError) {

            console.error(
                "Login request failed:",
                requestError
            );


            // -------------------------------------------------
            // SERVER RESPONSE
            // -------------------------------------------------

            if (
                requestError.response
            ) {

                const status =
                    requestError
                        .response
                        .status;

                const serverMessage =
                    requestError
                        .response
                        .data
                        ?.message;


                // ---------------------------------------------
                // UNAUTHORIZED
                // ---------------------------------------------

                if (
                    status === 401
                ) {

                    setError(
                        serverMessage ||
                        "Invalid Email ID or Password"
                    );

                    return;
                }


                // ---------------------------------------------
                // FORBIDDEN
                // ---------------------------------------------

                if (
                    status === 403
                ) {

                    setError(
                        serverMessage ||
                        "You do not have permission to login."
                    );

                    return;
                }


                // ---------------------------------------------
                // BAD REQUEST
                // ---------------------------------------------

                if (
                    status === 400
                ) {

                    setError(
                        serverMessage ||
                        "Please check your login details."
                    );

                    return;
                }


                // ---------------------------------------------
                // SERVER ERROR
                // ---------------------------------------------

                if (
                    status >= 500
                ) {

                    setError(
                        "Server error. Please try again later."
                    );

                    return;
                }


                // ---------------------------------------------
                // OTHER HTTP ERROR
                // ---------------------------------------------

                setError(
                    serverMessage ||
                    "Unable to complete login."
                );

                return;
            }


            // -------------------------------------------------
            // REQUEST WAS SENT BUT NO RESPONSE
            // -------------------------------------------------

            if (
                requestError.request
            ) {

                setError(
                    "Unable to connect to the server. Please check your connection."
                );

                return;
            }


            // -------------------------------------------------
            // UNKNOWN ERROR
            // -------------------------------------------------

            setError(
                "Something went wrong. Please try again."
            );

        }


        // =====================================================
        // FINALLY
        // =====================================================

        finally {

            setLoading(false);
        }
    };


    // =====================================================
    // PASSWORD VISIBILITY
    // =====================================================

    const togglePasswordVisibility = () => {

        setShowPassword(
            previous =>
                !previous
        );
    };


    // =====================================================
    // EMAIL CHANGE
    // =====================================================

    const handleEmailChange = (
        event
    ) => {

        setEmail(
            event.target.value
        );

        clearError();
    };


    // =====================================================
    // PASSWORD CHANGE
    // =====================================================

    const handlePasswordChange = (
        event
    ) => {

        setPassword(
            event.target.value
        );

        clearError();
    };


    // =====================================================
    // REMEMBER ME CHANGE
    // =====================================================

    const handleRememberMeChange = (
        event
    ) => {

        const checked =
            event.target.checked;

        setRememberMe(
            checked
        );

        if (!checked) {
            localStorage.removeItem(
                REMEMBERED_EMAIL_KEY
            );
        }
    };


    // =====================================================
    // JSX
    // =====================================================

    return (

        <div className="login-container">


            <form
                className="form"
                onSubmit={handleLogin}
                noValidate
            >


                {/* =================================================
                    LOGO / TITLE
                ================================================= */}

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
                            Faculty Login
                        </h2>

                    </div>

                </div>


                {/* =================================================
                    EMAIL
                ================================================= */}

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


                {/* =================================================
                    PASSWORD
                ================================================= */}

                <div className="inputForm">

                    <FaLock
                        className="icon"
                        aria-hidden="true"
                    />

                    <input
                        type={
                            showPassword
                                ? "text"
                                : "password"
                        }
                        className="input"
                        placeholder="Password"
                        value={password}
                        onChange={
                            handlePasswordChange
                        }
                        autoComplete="current-password"
                        disabled={loading}
                        aria-label="Password"
                        aria-required="true"
                        required
                    />


                    <button
                        type="button"
                        className="password-toggle"
                        onClick={
                            togglePasswordVisibility
                        }
                        disabled={loading}
                        aria-label={
                            showPassword
                                ? "Hide password"
                                : "Show password"
                        }
                        title={
                            showPassword
                                ? "Hide password"
                                : "Show password"
                        }
                    >

                        {showPassword ? (

                            <FaEyeSlash />

                        ) : (

                            <FaEye />

                        )}
                    </button>

                </div>


                {/* =================================================
                    REMEMBER ME / FORGOT PASSWORD
                ================================================= */}

                <div className="flex-row">


                    <div className="remember">

                        <input
                            type="checkbox"
                            id="remember"
                            checked={rememberMe}
                            onChange={
                                handleRememberMeChange
                            }
                            disabled={loading}
                        />

                        <label
                            htmlFor="remember"
                        >
                            Remember Me
                        </label>

                    </div>

                    <Link
                        to="/forgot-password"
                        className="forgot"
                    >
                        Forgot Password?
                    </Link>

                </div>


                {/* =================================================
                    ERROR MESSAGE
                ================================================= */}

                {activationNotice && (

                    <p
                        className="login-error"
                        style={{ color: "#16a34a" }}
                        role="status"
                        aria-live="polite"
                    >
                        {activationNotice}
                    </p>

                )}

                {error && (

                    <p
                        className="login-error"
                        role="alert"
                        aria-live="polite"
                    >
                        {error}
                    </p>

                )}


                {/* =================================================
                    LOGIN BUTTON
                ================================================= */}

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
                                    Checking...
                                </span>

                            </span>

                        ) : (

                            <span>
                                LOGIN
                            </span>

                        )}
                    </button>

                </div>


                {/* =================================================
                    FOOTER
                ================================================= */}

                <p className="footer">
                    © 2026 Faculty Sync Tracker
                </p>


            </form>

        </div>
    );
}

export default LoginPage;
