import "./AdminDashboard.css";

import { useEffect, useMemo, useState, useRef } from "react";
import { useNavigate } from "react-router-dom";
import dayjs from "dayjs";

import {
    LuUser,
    LuSearch,
    LuClock3,
    LuArrowRight,
    LuCheckCircle,
    LuXCircle,
    LuUsers,
    LuLogOut,
    LuCalendar,
    LuBuilding,
    LuMapPin,
    LuAlertCircle,
    LuKey,
    LuMegaphone,
    LuX
} from "react-icons/lu";

import { attendanceAPI, teamAPI, imageSrc } from "../services/api";
import { useAuth } from "../context/AuthContext";
import WebcamCapture from "../components/common/WebcamCapture";

// =============================================================
// LOCATION CONFIGURATION — backend-authoritative
//
// GET /attendance/admin/location-config (admin-tier roles only) returns
// { latitude, longitude, radiusMeters, maxAccuracyMeters }. We fetch the
// campus RADIUS (pre-check) and the max reported GPS ACCURACY (display
// hint) from there so runtime configuration changes are picked up without
// a frontend rebuild. Values are cached in-module and refreshed before
// each clock-in pre-check.
//
// The documented fallbacks (3,800 m campus radius, 150 m GPS accuracy
// hint) are used ONLY when the configuration cannot be retrieved. The
// backend remains the sole authority on attendance eligibility — every
// client-side check here is advisory and is re-validated server-side.
// =============================================================
const LOCATION_CONFIG_FALLBACK = Object.freeze({
    radiusMeters: 3800,
    maxAccuracyMeters: 150,
});
const LOCATION_CONFIG_CACHE_MAX_AGE_MS = 60 * 1000;
let locationConfigCache = null; // { config, fetchedAt } | null

/**
 * Load the authoritative location configuration, using the in-module cache
 * unless `force` is set. Never rejects: on failure it returns the last
 * successfully retrieved config, or the documented fallback when nothing
 * was ever retrieved.
 */
async function loadLocationConfig(force = false) {
    const now = Date.now();
    if (
        !force &&
        locationConfigCache &&
        locationConfigCache.config &&
        now - locationConfigCache.fetchedAt < LOCATION_CONFIG_CACHE_MAX_AGE_MS
    ) {
        return locationConfigCache.config;
    }
    try {
        const res = await attendanceAPI.getLocationConfig();
        const data = res && res.data;
        const radiusMeters = Number(data && data.radiusMeters);
        const maxAccuracyMeters = Number(data && data.maxAccuracyMeters);
        if (!Number.isFinite(radiusMeters) || radiusMeters <= 0) {
            throw new Error("Location config has no valid radiusMeters");
        }
        if (!Number.isFinite(maxAccuracyMeters) || maxAccuracyMeters <= 0) {
            throw new Error("Location config has no valid maxAccuracyMeters");
        }
        const config = { radiusMeters, maxAccuracyMeters };
        locationConfigCache = { config, fetchedAt: now };
        return config;
    } catch (err) {
        console.warn("Location configuration unavailable; falling back to documented defaults.", err);
        // A previously retrieved config still beats a hard-coded fallback;
        // the documented defaults apply only when nothing was ever retrieved.
        return (locationConfigCache && locationConfigCache.config) || LOCATION_CONFIG_FALLBACK;
    }
}

/** Map a GeolocationPositionError code into an actionable diagnostic message. */
const describeGeolocationError = (locErr) => {
    switch (locErr && locErr.code) {
        case 1: // GeolocationPositionError.PERMISSION_DENIED
            return "Location permission denied. Please enable location access in your browser settings to verify you are on campus.";
        case 2: // GeolocationPositionError.POSITION_UNAVAILABLE
            return "GPS signal unavailable. Ensure location/GPS is toggled on in your device settings.";
        case 3: // GeolocationPositionError.TIMEOUT
            return "Location request timed out. Please check your network or move to an area with better GPS reception.";
        default:
            return "Unable to get location. Please try again.";
    }
};


function AdminDashboard() {

    const navigate = useNavigate();
    const { user, logout } = useAuth();

    // Profile Dropdown state
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


    // =====================================================
    // COLLEGE LOCATION
    // =====================================================

    const COLLEGE_LATITUDE = Number(import.meta.env.VITE_COLLEGE_LATITUDE);
    const COLLEGE_LONGITUDE = Number(import.meta.env.VITE_COLLEGE_LONGITUDE);

    // Backend-authoritative values (fetched above). The documented
    // fallbacks apply only while the configuration cannot be retrieved.
    const [locationConfig, setLocationConfig] = useState(LOCATION_CONFIG_FALLBACK);
    const ALLOWED_RADIUS_METERS = locationConfig.radiusMeters;
    const MAX_REPORTED_ACCURACY_METERS = locationConfig.maxAccuracyMeters;


    // =====================================================
    // STATE
    // =====================================================

    const [currentDate, setCurrentDate] = useState(new Date());
    const [clockInTime, setClockInTime] = useState(null);
    const [clockOutTime, setClockOutTime] = useState(null);
    const [isClockedIn, setIsClockedIn] = useState(false);
    const [timerNow, setTimerNow] = useState(null);
    const [attendanceLoading, setAttendanceLoading] = useState(true);
    const [clockActionLoading, setClockActionLoading] = useState(false);

    // Face + GPS verification modal (punch-in now requires a verification
    // ticket issued by /attendance/verify, same flow as faculty)
    const [showVerifyModal, setShowVerifyModal] = useState(false);
    const [verifyPhotoBlob, setVerifyPhotoBlob] = useState(null);
    const [verifyCoords, setVerifyCoords] = useState(null);
    const [verifyLoading, setVerifyLoading] = useState(false);
    const [verifyError, setVerifyError] = useState("");

    const [locationLoading, setLocationLoading] = useState(false);
    const [locationError, setLocationError] = useState("");
    const [locationVerified, setLocationVerified] = useState(false);
    const [currentDistance, setCurrentDistance] = useState(null);
    const [apiError, setApiError] = useState("");

    // ALL FACULTY
    const [allFaculty, setAllFaculty] = useState([]);
    const [facultyLoading, setFacultyLoading] = useState(true);
    const [facultySearch, setFacultySearch] = useState("");

    // FILTER TAB: "ALL" | "CLOCKED_IN" | "CLOCKED_OUT"
    const [facultyFilter, setFacultyFilter] = useState("ALL");

    // MONTHLY ATTENDANCE HISTORY (FACULTY VIEW FOR ADMIN)
    const todayForHistory = new Date();
    const [historyMonth, setHistoryMonth] = useState(todayForHistory.getMonth());
    const [historyYear, setHistoryYear] = useState(todayForHistory.getFullYear());
    const [historyData, setHistoryData] = useState([]);
    const [historyLoading, setHistoryLoading] = useState(true);
    const [historyError, setHistoryError] = useState("");
    const [selectedHistoryDate, setSelectedHistoryDate] = useState(null);


    // =====================================================
    // HELPERS
    // =====================================================

    const isLocationConfigured = () => {
        return (
            Number.isFinite(COLLEGE_LATITUDE) &&
            Number.isFinite(COLLEGE_LONGITUDE) &&
            Number.isFinite(ALLOWED_RADIUS_METERS) &&
            ALLOWED_RADIUS_METERS > 0
        );
    };

    const calculateDistance = (lat1, lon1, lat2, lon2) => {
        const R = 6371000;
        const toRad = (deg) => (deg * Math.PI) / 180;
        const dLat = toRad(lat2 - lat1);
        const dLon = toRad(lon2 - lon1);
        const a =
            Math.sin(dLat / 2) ** 2 +
            Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
            Math.sin(dLon / 2) ** 2;
        const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    };


    // =====================================================
    // LOAD AUTHORITATIVE LOCATION CONFIGURATION (once on mount;
    // refreshed again right before each clock-in pre-check)
    // =====================================================

    useEffect(() => {
        let cancelled = false;
        loadLocationConfig().then((config) => {
            if (!cancelled) setLocationConfig(config);
        });
        return () => {
            cancelled = true;
        };
    }, []);


    // =====================================================
    // TIMERS (No automatic stopwatch when not clocked in)
    // =====================================================

    useEffect(() => {
        // Keep date current without 1-second rerender storm
        const t = setInterval(() => setCurrentDate(new Date()), 60000);
        return () => clearInterval(t);
    }, []);

    useEffect(() => {
        if (!isClockedIn) return undefined;
        // Only run live timer when actually clocked in
        const t = setInterval(() => setTimerNow(new Date()), 1000);
        return () => clearInterval(t);
    }, [isClockedIn]);


    // =====================================================
    // LOAD TODAY ATTENDANCE
    // =====================================================

    useEffect(() => {
        const load = async () => {
            try {
                setAttendanceLoading(true);
                const res = await attendanceAPI.getTodayAttendance();
                const data = res.data;

                if (data && data.hasPunchedIn) {
                    const checkIn = data.punchInTime ? new Date(data.punchInTime) : null;
                    const checkOut = data.punchOutTime ? new Date(data.punchOutTime) : null;

                    if (checkIn) {
                        setClockInTime(checkIn);
                        setTimerNow(checkOut || new Date());
                        if (checkOut) {
                            setClockOutTime(checkOut);
                            setIsClockedIn(false);
                        } else {
                            setIsClockedIn(true);
                        }
                    }
                }
            } catch (err) {
                console.error("Attendance load error:", err);
            } finally {
                setAttendanceLoading(false);
            }
        };
        load();
    }, []);


    // =====================================================
    // LOAD ALL FACULTY (ADMIN)
    // =====================================================

    useEffect(() => {
        const load = async () => {
            try {
                setFacultyLoading(true);
                const res = await teamAPI.getDepartmentFaculty();
                setAllFaculty(Array.isArray(res.data) ? res.data : []);
            } catch (err) {
                console.error("Faculty list error:", err);
                setAllFaculty([]);
            } finally {
                setFacultyLoading(false);
            }
        };
        load();
    }, []);


    // =====================================================
    // LOAD MONTHLY ATTENDANCE HISTORY (FACULTY VIEW FOR ADMIN)
    // =====================================================

    useEffect(() => {
        const loadHistory = async () => {
            try {
                setHistoryLoading(true);
                setHistoryError("");
                const start = dayjs(new Date(historyYear, historyMonth, 1)).startOf("month").format("YYYY-MM-DD");
                const end = dayjs(new Date(historyYear, historyMonth, 1)).endOf("month").format("YYYY-MM-DD");
                const res = await attendanceAPI.getHistory(start, end);
                setHistoryData(Array.isArray(res.data) ? res.data : []);
            } catch (err) {
                console.error("Attendance history load error:", err);
                setHistoryError(err.response?.data?.message || "Unable to load attendance history.");
            } finally {
                setHistoryLoading(false);
            }
        };
        loadHistory();
    }, [historyMonth, historyYear]);


    // =====================================================
    // FILTERED FACULTY
    // =====================================================

    const filteredFaculty = useMemo(() => {
        let result = allFaculty;

        if (facultyFilter === "CLOCKED_IN") {
            result = result.filter((f) => f.isClockedIn);
        } else if (facultyFilter === "CLOCKED_OUT") {
            result = result.filter((f) => !f.isClockedIn);
        }

        if (facultySearch.trim()) {
            const query = facultySearch.toLowerCase().trim();
            result = result.filter((f) =>
                (f.fullName || "").toLowerCase().includes(query) ||
                (f.departmentName || "").toLowerCase().includes(query) ||
                (f.employeeId || "").toLowerCase().includes(query) ||
                (f.role || "").toLowerCase().includes(query)
            );
        }

        return result;
    }, [allFaculty, facultySearch, facultyFilter]);


    // =====================================================
    // COMPUTED DATE & ATTENDANCE VALUES
    // =====================================================

    const MONTH_NAMES = [
        "January", "February", "March", "April",
        "May", "June", "July", "August",
        "September", "October", "November", "December"
    ];

    const WEEK_DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

    const dateText = currentDate.toLocaleDateString("en-US", {
        day: "numeric",
        month: "long",
        year: "numeric"
    });

    const weekdayText = currentDate.toLocaleDateString("en-US", {
        weekday: "long"
    });

    const getLocalDateString = (date = new Date()) => {
        const y = date.getFullYear();
        const m = String(date.getMonth() + 1).padStart(2, "0");
        const d = String(date.getDate()).padStart(2, "0");
        return `${y}-${m}-${d}`;
    };

    const getHistoryRecord = (date) => {
        const ds = getLocalDateString(date);
        return historyData.find((r) => r.attendanceDate === ds) || null;
    };

    const getAttendanceStatus = (record, date) => {
        if (!record) {
            const today = new Date();
            today.setHours(0, 0, 0, 0);
            const check = new Date(date);
            check.setHours(0, 0, 0, 0);
            if (check > today) return "FUTURE";
            return "UNKNOWN";
        }
        const s = String(record.status || "").toUpperCase();
        if (s === "PRESENT" || record.punchInTime) return "PRESENT";
        if (s === "ABSENT") return "ABSENT";
        if (s === "ON_LEAVE") return "LEAVE";
        return "UNKNOWN";
    };

    const calendarDays = useMemo(() => {
        const first = new Date(historyYear, historyMonth, 1);
        const last = new Date(historyYear, historyMonth + 1, 0);
        const startDay = first.getDay();
        const totalDays = last.getDate();
        const days = [];
        for (let i = 0; i < startDay; i++) days.push({ empty: true, key: `e-${i}` });
        for (let d = 1; d <= totalDays; d++) days.push({ empty: false, date: new Date(historyYear, historyMonth, d) });
        return days;
    }, [historyMonth, historyYear]);

    const historySummary = useMemo(() => {
        let present = 0, absent = 0, leave = 0;
        historyData.forEach((r) => {
            const s = String(r.status || "").toUpperCase();
            if (s === "PRESENT" || r.punchInTime) present++;
            else if (s === "ABSENT") absent++;
            else if (s === "ON_LEAVE") leave++;
        });
        return { present, absent, leave };
    }, [historyData]);

    const formatClockInTime = () => {
        if (!clockInTime) return "";
        return clockInTime.toLocaleTimeString("en-US", {
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            hour12: true
        });
    };

    const calculateWorkedTime = () => {
        if (!clockInTime) return "00h 00m 00s";
        const endTime = isClockedIn ? timerNow : clockOutTime;
        if (!endTime) return "00h 00m 00s";
        const diff = Math.max(0, Math.floor((endTime.getTime() - clockInTime.getTime()) / 1000));
        const h = Math.floor(diff / 3600);
        const m = Math.floor((diff % 3600) / 60);
        const s = diff % 60;
        return `${String(h).padStart(2, "0")}h ${String(m).padStart(2, "0")}m ${String(s).padStart(2, "0")}s`;
    };

    const clockedInCount = allFaculty.filter((f) => f.isClockedIn).length;
    const clockedOutCount = Math.max(0, allFaculty.length - clockedInCount);


    // =====================================================
    // CLOCK IN
    // =====================================================

    const handleClockIn = async () => {
        setLocationError("");
        setApiError("");
        setLocationVerified(false);
        setCurrentDistance(null);

        if (!isLocationConfigured()) {
            setLocationError("College location not configured. Contact admin.");
            return;
        }
        if (!navigator.geolocation) {
            setLocationError("Location not supported by this browser.");
            return;
        }

        setLocationLoading(true);

        // Refresh the authoritative backend config (force, bypassing the
        // cache) so the pre-check below reflects any runtime change made
        // by an admin — no frontend rebuild required. Advisory only; the
        // backend re-validates geofence, accuracy and biometrics itself.
        const cfg = await loadLocationConfig(true);
        setLocationConfig(cfg);
        const allowedRadiusMeters = cfg.radiusMeters;

        navigator.geolocation.getCurrentPosition(
            async (position) => {
                try {
                    const { latitude, longitude, accuracy } = position.coords;
                    const distance = calculateDistance(latitude, longitude, COLLEGE_LATITUDE, COLLEGE_LONGITUDE);
                    setCurrentDistance(distance);

                    if (distance > allowedRadiusMeters) {
                        setLocationError(`Outside attendance area. Distance: ${Math.round(distance)}m. Allowed: ${allowedRadiusMeters}m.`);
                        setLocationLoading(false);
                        return;
                    }

                    setLocationVerified(true);
                    // Location passed — now capture a face photo and get a
                    // verification ticket before the punch-in (backend contract).
                    setVerifyCoords({ latitude, longitude, accuracy });
                    setVerifyPhotoBlob(null);
                    setVerifyError("");
                    setShowVerifyModal(true);
                } catch (err) {
                    console.error("Clock in error:", err);
                    setApiError(err.response?.data?.message || "Unable to complete Clock In.");
                    setLocationVerified(false);
                } finally {
                    setLocationLoading(false);
                    setClockActionLoading(false);
                }
            },
            (err) => {
                setLocationLoading(false);
                setLocationError(describeGeolocationError(err));
            },
            { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }
        );
    };


    // =====================================================
    // VERIFY (FACE) & CLOCK IN — uses the single-use ticket
    // =====================================================

    const handleVerifyAndClockIn = async () => {
        if (!verifyPhotoBlob) {
            setVerifyError("Please capture a photo first.");
            return;
        }
        setVerifyLoading(true);
        setVerifyError("");

        try {
            const blob = verifyPhotoBlob;
            const base64 = await new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onloadend = () => resolve(reader.result);
                reader.onerror = reject;
                reader.readAsDataURL(blob);
            });

            const verifyRes = await attendanceAPI.verifyAttendance({
                capturedPhoto: base64,
                latitude: verifyCoords.latitude,
                longitude: verifyCoords.longitude,
                accuracy: verifyCoords.accuracy,
            });
            const verifyResult = verifyRes.data;

            if (!verifyResult.verified || !verifyResult.ticket) {
                setVerifyError(verifyResult.message || "Verification failed. Please try again.");
                return;
            }

            const res = await attendanceAPI.punchIn(verifyResult.ticket);
            const data = res.data;
            const checkInTime = data.punchInTime ? new Date(data.punchInTime) : new Date();

            setClockInTime(checkInTime);
            setClockOutTime(null);
            setTimerNow(checkInTime);
            setIsClockedIn(true);

            // Refresh faculty list
            const facultyRes = await teamAPI.getDepartmentFaculty();
            setAllFaculty(Array.isArray(facultyRes.data) ? facultyRes.data : []);

            setShowVerifyModal(false);
            setVerifyPhotoBlob(null);
        } catch (err) {
            console.error("Verify & clock in error:", err);
            setVerifyError(err.response?.data?.message || "Verification failed. Please try again.");
        } finally {
            setVerifyLoading(false);
        }
    };

    const closeVerifyModal = () => {
        if (verifyLoading) return;
        setShowVerifyModal(false);
        setVerifyPhotoBlob(null);
        setVerifyError("");
    };

    /**
     * In-modal retry after a failed verification: clears the error and the
     * captured photo so a fresh capture + verification can run without
     * closing the modal or reloading the page. The camera stream is managed
     * cleanly by WebcamCapture's own retake flow.
     */
    const retryVerification = () => {
        setVerifyError("");
        setVerifyPhotoBlob(null);
    };

    // =====================================================
    // CLOCK OUT
    // =====================================================

    const handleClockOut = async () => {
        setApiError("");
        setLocationError("");
        setClockActionLoading(true);

        try {
            const res = await attendanceAPI.punchOut();
            const data = res.data;
            const checkOut = data.punchOutTime ? new Date(data.punchOutTime) : new Date();

            setClockOutTime(checkOut);
            setTimerNow(checkOut);
            setIsClockedIn(false);

            // Refresh faculty list
            const facultyRes = await teamAPI.getDepartmentFaculty();
            setAllFaculty(Array.isArray(facultyRes.data) ? facultyRes.data : []);
        } catch (err) {
            console.error("Clock out error:", err);
            setApiError(err.response?.data?.message || "Unable to complete Clock Out.");
        } finally {
            setClockActionLoading(false);
        }
    };


    // =====================================================
    // RENDER
    // =====================================================

    return (

        <div className="adm-dashboard">


            {/* =================================================
                1. TOP NAVIGATION BAR (Clean profile icon + dropdown)
            ================================================= */}

            <header className="adm-header">
                <div className="adm-header-left" ref={profileDropdownRef} style={{ position: "relative" }}>
                    <button
                        type="button"
                        className="adm-profile-btn"
                        onClick={() => setProfileDropdownOpen((prev) => !prev)}
                        aria-label="Admin Profile Menu"
                        aria-expanded={profileDropdownOpen}
                        title={`Head of Department: ${user?.fullName || "Rajesh Kumar"} (${user?.email || "hod.cse@college.edu"})`}
                    >
                        <LuUser className="adm-profile-icon" />
                    </button>

                    {profileDropdownOpen && (
                        <div className="adm-profile-dropdown" role="menu">
                            <div className="adm-dropdown-user-header">
                                <div className="adm-dropdown-avatar">
                                    <LuUser />
                                </div>
                                <div className="adm-dropdown-meta">
                                    <span className="adm-dropdown-name">{user?.fullName || "Rajesh Kumar"}</span>
                                    <span className="adm-dropdown-email">{user?.email || "hod.cse@college.edu"}</span>
                                    <span className="adm-dropdown-role-tag">Head of Department</span>
                                </div>
                            </div>
                            <div className="adm-dropdown-divider" />
                            <button
                                type="button"
                                className="adm-dropdown-item"
                                role="menuitem"
                                onClick={() => {
                                    setProfileDropdownOpen(false);
                                    navigate("/admin/profile");
                                }}
                            >
                                <LuUser className="adm-dropdown-icon" />
                                <span>Profile Details</span>
                            </button>
                            <button
                                type="button"
                                className="adm-dropdown-item"
                                role="menuitem"
                                onClick={() => {
                                    setProfileDropdownOpen(false);
                                    navigate("/change-password");
                                }}
                            >
                                <LuKey className="adm-dropdown-icon" />
                                <span>Change Password</span>
                            </button>
                            <div className="adm-dropdown-divider" />
                            <button
                                type="button"
                                className="adm-dropdown-item adm-dropdown-logout"
                                role="menuitem"
                                onClick={() => {
                                    setProfileDropdownOpen(false);
                                    logout();
                                    navigate("/login", { replace: true });
                                }}
                            >
                                <LuLogOut className="adm-dropdown-icon" />
                                <span>Logout</span>
                            </button>
                        </div>
                    )}
                </div>

                <div className="adm-header-center">
                    <div className="adm-search-bar">
                        <LuSearch className="adm-search-icon" />
                        <input
                            type="text"
                            placeholder="Search faculty..."
                            value={facultySearch}
                            onChange={(e) => setFacultySearch(e.target.value)}
                        />
                        {facultySearch && (
                            <button
                                type="button"
                                className="adm-search-clear"
                                onClick={() => setFacultySearch("")}
                                aria-label="Clear search"
                            >
                                ×
                            </button>
                        )}
                    </div>
                </div>

                <div className="adm-header-right">
                    <button
                        type="button"
                        className="adm-logout-btn"
                        onClick={() => {
                            logout();
                            navigate("/login", { replace: true });
                        }}
                    >
                        <LuLogOut className="adm-logout-icon" />
                        <span>Logout</span>
                    </button>
                </div>
            </header>


            {/* =================================================
                2. MAIN CONTENT AREA
            ================================================= */}

            <main className="adm-main">


                {/* =================================================
                    3. TODAY'S ATTENDANCE HERO CARD
                ================================================= */}

                <section className="adm-hero-card">
                    {/* Left Section */}
                    <div className="adm-hero-left">
                        <span className="adm-hero-tag">TODAY&apos;S ATTENDANCE</span>
                        <h2 className="adm-hero-status">
                            {isClockedIn ? "Working" : clockOutTime ? "Completed" : "Not Clocked In"}
                        </h2>
                        <p className="adm-hero-subtext">
                            {isClockedIn
                                ? "You are currently clocked in and active for today."
                                : clockOutTime
                                ? "You have completed your attendance for today."
                                : "You haven't clocked in yet for today. Please clock in to mark your attendance."}
                        </p>
                        <div className="adm-hero-meta">
                            <span className="adm-shift-pill">General Shift</span>
                            {clockInTime && (
                                <span className="adm-hero-time-pill">
                                    Clocked in: {formatClockInTime()}
                                    {isClockedIn && ` • Worked: ${calculateWorkedTime()}`}
                                </span>
                            )}
                        </div>
                    </div>

                    {/* Center Section: Pill Button */}
                    <div className="adm-hero-center">
                        {!isClockedIn ? (
                            <button
                                type="button"
                                className="adm-clock-btn adm-clock-in-btn"
                                onClick={handleClockIn}
                                disabled={locationLoading || clockActionLoading || attendanceLoading}
                            >
                                {locationLoading || clockActionLoading ? (
                                    <span className="adm-spinner" />
                                ) : (
                                    <LuClock3 className="adm-btn-clock-icon" />
                                )}
                                <span>
                                    {locationLoading
                                        ? "Verifying Location..."
                                        : clockActionLoading
                                        ? "Processing..."
                                        : clockOutTime
                                        ? "Clock In Again"
                                        : "Clock In"}
                                </span>
                                <LuArrowRight className="adm-btn-arrow-icon" />
                            </button>
                        ) : (
                            <button
                                type="button"
                                className="adm-clock-btn adm-clock-out-btn"
                                onClick={handleClockOut}
                                disabled={clockActionLoading}
                            >
                                {clockActionLoading ? (
                                    <span className="adm-spinner" />
                                ) : (
                                    <LuClock3 className="adm-btn-clock-icon" />
                                )}
                                <span>{clockActionLoading ? "Processing..." : "Clock Out"}</span>
                                <LuArrowRight className="adm-btn-arrow-icon" />
                            </button>
                        )}

                        {locationVerified && (
                            <div className="adm-status-msg adm-status-success">
                                <LuMapPin />
                                <span>Location Verified {currentDistance !== null ? `(${Math.round(currentDistance)}m)` : ""}</span>
                            </div>
                        )}
                        {locationError && (
                            <div className="adm-status-msg adm-status-error">
                                <LuAlertCircle />
                                <span>{locationError}</span>
                            </div>
                        )}
                        {apiError && (
                            <div className="adm-status-msg adm-status-error">
                                <LuAlertCircle />
                                <span>{apiError}</span>
                            </div>
                        )}
                    </div>

                    {/* Right Section: Real Date & Shift info (Timer only when clocked in) */}
                    <div className="adm-hero-right">
                        <div className="adm-hero-divider" />
                        <div className="adm-hero-datetime">
                            <div className="adm-hero-date">{dateText}</div>
                            <div className="adm-hero-weekday">{weekdayText}</div>
                            <div className="adm-hero-shift-timing">Shift: 09:00 AM – 05:00 PM</div>
                            {isClockedIn && (
                                <div className="adm-hero-active-timer">
                                    <span className="adm-timer-label">ACTIVE SESSION</span>
                                    <span className="adm-timer-value">{calculateWorkedTime()}</span>
                                </div>
                            )}
                        </div>
                    </div>
                </section>


                {/* =================================================
                    4. THREE STATISTICS CARDS
                ================================================= */}

                <section className="adm-stats-grid">
                    {/* Card 1 — Clocked In */}
                    <div
                        className={`adm-stat-card adm-stat-clocked-in ${facultyFilter === "CLOCKED_IN" ? "adm-stat-selected" : ""}`}
                        onClick={() => setFacultyFilter(facultyFilter === "CLOCKED_IN" ? "ALL" : "CLOCKED_IN")}
                        role="button"
                        tabIndex={0}
                        aria-pressed={facultyFilter === "CLOCKED_IN"}
                    >
                        <div className="adm-stat-top">
                            <div className="adm-stat-icon-wrap adm-icon-green">
                                <LuCheckCircle />
                            </div>
                            {allFaculty.length > 0 && (
                                <span className="adm-stat-trend adm-trend-green">
                                    {Math.round((clockedInCount / allFaculty.length) * 100)}% active
                                </span>
                            )}
                        </div>
                        <div className="adm-stat-value">{clockedInCount}</div>
                        <div className="adm-stat-label">Clocked In</div>
                        <div className="adm-stat-desc">Faculty currently present on campus</div>
                    </div>

                    {/* Card 2 — Clocked Out */}
                    <div
                        className={`adm-stat-card adm-stat-clocked-out ${facultyFilter === "CLOCKED_OUT" ? "adm-stat-selected" : ""}`}
                        onClick={() => setFacultyFilter(facultyFilter === "CLOCKED_OUT" ? "ALL" : "CLOCKED_OUT")}
                        role="button"
                        tabIndex={0}
                        aria-pressed={facultyFilter === "CLOCKED_OUT"}
                    >
                        <div className="adm-stat-top">
                            <div className="adm-stat-icon-wrap adm-icon-red">
                                <LuXCircle />
                            </div>
                            {allFaculty.length > 0 && (
                                <span className="adm-stat-trend adm-trend-red">
                                    {clockedOutCount} away
                                </span>
                            )}
                        </div>
                        <div className="adm-stat-value">{clockedOutCount}</div>
                        <div className="adm-stat-label">Clocked Out</div>
                        <div className="adm-stat-desc">Faculty not currently clocked in</div>
                    </div>

                    {/* Card 3 — Total Faculty */}
                    <div
                        className={`adm-stat-card adm-stat-total ${facultyFilter === "ALL" ? "adm-stat-selected" : ""}`}
                        onClick={() => setFacultyFilter("ALL")}
                        role="button"
                        tabIndex={0}
                        aria-pressed={facultyFilter === "ALL"}
                    >
                        <div className="adm-stat-top">
                            <div className="adm-stat-icon-wrap adm-icon-blue">
                                <LuUsers />
                            </div>
                            <span className="adm-stat-trend adm-trend-blue">
                                All staff
                            </span>
                        </div>
                        <div className="adm-stat-value">{allFaculty.length}</div>
                        <div className="adm-stat-label">Total Faculty</div>
                        <div className="adm-stat-desc">{allFaculty.length} registered members</div>
                    </div>
                </section>


                {/* =================================================
                    5. ALL FACULTY SECTION
                ================================================= */}

                <section className="adm-faculty-section">
                    <div className="adm-section-header">
                        <div className="adm-header-title-wrap">
                            <div className="adm-vertical-accent" />
                            <div>
                                <h2 className="adm-section-title">
                                    {facultyFilter === "CLOCKED_IN"
                                        ? "Clocked In Faculty"
                                        : facultyFilter === "CLOCKED_OUT"
                                        ? "Clocked Out Faculty"
                                        : "All Faculty"}
                                </h2>
                                <p className="adm-section-subtitle">View and search faculty members</p>
                            </div>
                        </div>

                        <div className="adm-faculty-controls">
                            <span className="adm-count-pill">
                                {filteredFaculty.length} Faculty
                            </span>
                            <div className="adm-inline-search">
                                <LuSearch className="adm-inline-search-icon" />
                                <input
                                    type="text"
                                    placeholder="Search faculty..."
                                    value={facultySearch}
                                    onChange={(e) => setFacultySearch(e.target.value)}
                                />
                            </div>
                        </div>
                    </div>

                    {/* Faculty Grid or Empty State */}
                    {facultyLoading ? (
                        <div className="adm-loading-box">
                            <div className="adm-spinner" />
                            <span>Loading faculty directory...</span>
                        </div>
                    ) : filteredFaculty.length > 0 ? (
                        <div className="adm-faculty-grid">
                            {filteredFaculty.map((f) => (
                                <div
                                    key={f.employeeId || f.id}
                                    className={`adm-faculty-card ${f.isClockedIn ? "adm-card-in" : "adm-card-out"}`}
                                >
                                    <div className="adm-faculty-avatar-wrap">
                                        <div className={`adm-faculty-avatar ${f.isClockedIn ? "adm-avatar-in" : "adm-avatar-out"}`} style={{ overflow: "hidden" }}>
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
                                                        {f.fullName ? f.fullName.charAt(0).toUpperCase() : <LuUser />}
                                                    </span>
                                                </>
                                            ) : (
                                                <span>{f.fullName ? f.fullName.charAt(0).toUpperCase() : <LuUser />}</span>
                                            )}
                                        </div>
                                        {f.isClockedIn && <span className="adm-online-badge" />}
                                    </div>

                                    <div className="adm-faculty-details">
                                        <div className="adm-faculty-name-row">
                                            <span className="adm-faculty-name">{f.fullName}</span>
                                        </div>
                                        <div className="adm-faculty-dept">
                                            <LuBuilding className="adm-dept-icon" />
                                            <span>{f.departmentName || "General Faculty"}</span>
                                        </div>
                                        <span className="adm-role-badge">{f.role || "Faculty"}</span>
                                    </div>

                                    <div className="adm-faculty-status-col">
                                        {f.isClockedIn ? (
                                            <span className="adm-status-pill adm-pill-in">
                                                <LuCheckCircle /> In
                                            </span>
                                        ) : (
                                            <span className="adm-status-pill adm-pill-out">
                                                <LuXCircle /> Out
                                            </span>
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    ) : (
                        /* Empty State: Clean text-only (No icon/image per strict prompt rule) */
                        <div className="adm-empty-state">
                            <h3 className="adm-empty-title">No faculty found</h3>
                            <p className="adm-empty-desc">
                                {facultySearch
                                    ? "No faculty members match your search criteria."
                                    : "There are no faculty members to display at the moment."}
                            </p>
                        </div>
                    )}
                </section>


                {/* =================================================
                    6. APPLY LEAVE, LEAVE BALANCE & APPROVALS SHORTCUT CARDS
                ================================================= */}

                <section className="adm-shortcut-grid">
                    {/* Apply Leave */}
                    <div
                        className="adm-shortcut-card adm-card-apply-leave"
                        onClick={() => navigate("/admin/apply-leave")}
                        role="button"
                        tabIndex={0}
                    >
                        <div className="adm-shortcut-left">
                            <div className="adm-shortcut-icon-wrap adm-icon-blue-bg">
                                <LuCalendar className="adm-shortcut-icon" />
                            </div>
                            <div>
                                <h3 className="adm-shortcut-title">Apply Leave</h3>
                                <p className="adm-shortcut-desc">Submit a new leave request</p>
                            </div>
                        </div>
                        <div className="adm-shortcut-arrow adm-arrow-blue">
                            <LuArrowRight />
                        </div>
                    </div>

                    {/* Leave Balance */}
                    <div
                        className="adm-shortcut-card adm-card-leave-balance"
                        onClick={() => navigate("/admin/leave-balance")}
                        role="button"
                        tabIndex={0}
                    >
                        <div className="adm-shortcut-left">
                            <div className="adm-shortcut-icon-wrap adm-icon-orange-bg">
                                <LuClock3 className="adm-shortcut-icon" />
                            </div>
                            <div>
                                <h3 className="adm-shortcut-title">Leave Balance</h3>
                                <p className="adm-shortcut-desc">View your available leaves</p>
                            </div>
                        </div>
                        <div className="adm-shortcut-arrow adm-arrow-orange">
                            <LuArrowRight />
                        </div>
                    </div>

                    {/* Leave Approvals */}
                    <div
                        className="adm-shortcut-card adm-card-leave-approvals"
                        onClick={() => navigate("/admin/leaves")}
                        role="button"
                        tabIndex={0}
                    >
                        <div className="adm-shortcut-left">
                            <div className="adm-shortcut-icon-wrap adm-icon-green-bg">
                                <LuCheckCircle className="adm-shortcut-icon" />
                            </div>
                            <div>
                                <h3 className="adm-shortcut-title">Leave Approvals</h3>
                                <p className="adm-shortcut-desc">Review pending faculty requests</p>
                            </div>
                        </div>
                        <div className="adm-shortcut-arrow adm-arrow-green">
                            <LuArrowRight />
                        </div>
                    </div>
                </section>


                {/* =================================================
                    7. ANNOUNCEMENT SECTION (Faculty Notice)
                ================================================= */}

                <section className="adm-announcement-section">
                    <div className="adm-announcement-heading">
                        <LuMegaphone className="adm-announcement-heading-icon" />
                        <h2>Announcement</h2>
                    </div>
                    <div className="adm-announcement-card">
                        <div className="adm-announcement-icon-wrap">
                            <LuMegaphone />
                        </div>
                        <div className="adm-announcement-body">
                            <h3>Important Faculty Notice</h3>
                            <p>Please ensure your daily attendance is marked correctly before leaving the campus.</p>
                        </div>
                        <span className="adm-announcement-tag">Today</span>
                    </div>
                </section>


                {/* =================================================
                    8. MONTHLY ATTENDANCE HISTORY SECTION
                ================================================= */}

                <section className="adm-history-section">
                    <div className="adm-section-header">
                        <div className="adm-header-title-wrap">
                            <div className="adm-vertical-accent" />
                            <div>
                                <h2 className="adm-section-title">Monthly Attendance</h2>
                                <p className="adm-section-subtitle">Your personal attendance history and monthly summary</p>
                            </div>
                        </div>
                    </div>

                    <div className="adm-history-card">
                        <div className="adm-history-month-header">
                            <div className="adm-history-month-info">
                                <LuCalendar className="adm-history-month-icon" />
                                <div>
                                    <strong>{MONTH_NAMES[historyMonth]}</strong>
                                    <span>{historyYear}</span>
                                </div>
                            </div>
                            <div className="adm-history-month-nav">
                                <button
                                    type="button"
                                    className="adm-month-nav-btn"
                                    onClick={() => {
                                        if (historyMonth === 0) {
                                            setHistoryMonth(11);
                                            setHistoryYear((y) => y - 1);
                                        } else {
                                            setHistoryMonth((m) => m - 1);
                                        }
                                        setSelectedHistoryDate(null);
                                    }}
                                    aria-label="Previous month"
                                >
                                    ‹
                                </button>
                                <button
                                    type="button"
                                    className="adm-month-nav-btn adm-month-today-btn"
                                    onClick={() => {
                                        const now = new Date();
                                        setHistoryMonth(now.getMonth());
                                        setHistoryYear(now.getFullYear());
                                        setSelectedHistoryDate(null);
                                    }}
                                >
                                    Today
                                </button>
                                <button
                                    type="button"
                                    className="adm-month-nav-btn"
                                    onClick={() => {
                                        if (historyMonth === 11) {
                                            setHistoryMonth(0);
                                            setHistoryYear((y) => y + 1);
                                        } else {
                                            setHistoryMonth((m) => m + 1);
                                        }
                                        setSelectedHistoryDate(null);
                                    }}
                                    aria-label="Next month"
                                >
                                    ›
                                </button>
                            </div>
                        </div>

                        {!historyLoading && (
                            <div className="adm-history-summary">
                                <div className="adm-history-summary-item">
                                    <div className="adm-summary-item-top">
                                        <span className="adm-history-summary-dot adm-dot-present" />
                                        <strong>{historySummary.present}</strong>
                                    </div>
                                    <span className="adm-summary-item-label">Present</span>
                                </div>
                                <div className="adm-history-summary-item">
                                    <div className="adm-summary-item-top">
                                        <span className="adm-history-summary-dot adm-dot-absent" />
                                        <strong>{historySummary.absent}</strong>
                                    </div>
                                    <span className="adm-summary-item-label">Absent</span>
                                </div>
                                <div className="adm-history-summary-item">
                                    <div className="adm-summary-item-top">
                                        <span className="adm-history-summary-dot adm-dot-leave" />
                                        <strong>{historySummary.leave}</strong>
                                    </div>
                                    <span className="adm-summary-item-label">Leave</span>
                                </div>
                            </div>
                        )}

                        {historyError && (
                            <div className="adm-history-error">
                                <LuAlertCircle />
                                <span>{historyError}</span>
                            </div>
                        )}

                        {historyLoading ? (
                            <div className="adm-history-loading">
                                <div className="adm-spinner" />
                                <span>Loading attendance history...</span>
                            </div>
                        ) : (
                            <div className="adm-history-calendar">
                                <div className="adm-history-weekdays">
                                    {WEEK_DAYS.map((d) => (
                                        <span key={d}>{d}</span>
                                    ))}
                                </div>

                                <div className="adm-history-grid">
                                    {calendarDays.map((item) => {
                                        if (item.empty) {
                                            return <div className="adm-history-empty" key={item.key} />;
                                        }

                                        const date = item.date;
                                        const record = getHistoryRecord(date);
                                        const status = getAttendanceStatus(record, date);
                                        const ds = getLocalDateString(date);
                                        const isToday = getLocalDateString() === ds;
                                        const isSelected = selectedHistoryDate?.dateString === ds;

                                        let cls = "adm-history-unknown";
                                        if (status === "PRESENT") cls = "adm-history-present";
                                        else if (status === "ABSENT") cls = "adm-history-absent";
                                        else if (status === "LEAVE") cls = "adm-history-leave";
                                        else if (status === "FUTURE") cls = "adm-history-future";

                                        return (
                                            <button
                                                type="button"
                                                key={ds}
                                                className={`adm-history-day ${cls} ${isToday ? "adm-history-today" : ""} ${isSelected ? "adm-history-selected" : ""}`}
                                                onClick={() => setSelectedHistoryDate({ date, dateString: ds, record })}
                                                disabled={status === "FUTURE"}
                                                aria-label={`${ds}: ${status}`}
                                            >
                                                <span>{date.getDate()}</span>
                                            </button>
                                        );
                                    })}
                                </div>

                                <div className="adm-history-legend">
                                    <div className="adm-legend-item">
                                        <span className="adm-legend-box adm-history-unknown" />
                                        <span>No Record</span>
                                    </div>
                                    <div className="adm-legend-item">
                                        <span className="adm-legend-box adm-history-present" />
                                        <span>Present</span>
                                    </div>
                                    <div className="adm-legend-item">
                                        <span className="adm-legend-box adm-history-absent" />
                                        <span>Absent</span>
                                    </div>
                                    <div className="adm-legend-item">
                                        <span className="adm-legend-box adm-history-leave" />
                                        <span>Leave</span>
                                    </div>
                                </div>

                                {selectedHistoryDate && (
                                    <div className="adm-history-detail">
                                        <div className="adm-history-detail-header">
                                            <div>
                                                <span className="adm-detail-sublabel">SELECTED DATE</span>
                                                <h3 className="adm-detail-title">
                                                    {selectedHistoryDate.date.toLocaleDateString("en-IN", {
                                                        day: "2-digit",
                                                        month: "long",
                                                        year: "numeric"
                                                    })}
                                                </h3>
                                            </div>
                                            <button
                                                type="button"
                                                className="adm-history-detail-close"
                                                onClick={() => setSelectedHistoryDate(null)}
                                                aria-label="Close date detail"
                                            >
                                                <LuX />
                                            </button>
                                        </div>

                                        {selectedHistoryDate.record ? (
                                            <>
                                                <div
                                                    className={`adm-history-status-badge ${
                                                        selectedHistoryDate.record.status === "ABSENT"
                                                            ? "adm-status-badge-absent"
                                                            : selectedHistoryDate.record.status === "ON_LEAVE"
                                                            ? "adm-status-badge-leave"
                                                            : "adm-status-badge-present"
                                                    }`}
                                                >
                                                    <LuCheckCircle />
                                                    <span>{selectedHistoryDate.record.status || "PRESENT"}</span>
                                                </div>
                                                <div className="adm-history-details-grid">
                                                    <div className="adm-history-detail-col">
                                                        <span className="adm-detail-cell-label">PUNCH IN</span>
                                                        <strong className="adm-detail-cell-value">
                                                            {selectedHistoryDate.record.punchInTime
                                                                ? dayjs(selectedHistoryDate.record.punchInTime).format("HH:mm:ss")
                                                                : "--"}
                                                        </strong>
                                                    </div>
                                                    <div className="adm-history-detail-col">
                                                        <span className="adm-detail-cell-label">PUNCH OUT</span>
                                                        <strong className="adm-detail-cell-value">
                                                            {selectedHistoryDate.record.punchOutTime
                                                                ? dayjs(selectedHistoryDate.record.punchOutTime).format("HH:mm:ss")
                                                                : "--"}
                                                        </strong>
                                                    </div>
                                                    <div className="adm-history-detail-col">
                                                        <span className="adm-detail-cell-label">WORKED</span>
                                                        <strong className="adm-detail-cell-value">
                                                            {selectedHistoryDate.record.workingHours
                                                                ? `${selectedHistoryDate.record.workingHours}h`
                                                                : "--"}
                                                        </strong>
                                                    </div>
                                                </div>
                                            </>
                                        ) : (
                                            <div className="adm-history-status-badge adm-status-badge-unknown">
                                                <LuAlertCircle />
                                                <span>No attendance record for this date.</span>
                                            </div>
                                        )}
                                    </div>
                                )}
                            </div>
                        )}
                    </div>
                </section>

            </main>


            {/* =================================================
                7. BOTTOM NAVIGATION
            ================================================= */}

            <nav className="adm-bottom-nav" aria-label="Dashboard navigation">
                <button
                    type="button"
                    className="adm-nav-item adm-nav-active"
                    aria-current="page"
                >
                    <LuUsers className="adm-nav-icon" />
                    <span className="adm-nav-label">MONITOR</span>
                    <span className="adm-nav-underline" />
                </button>
            </nav>

            {/* =================================================
                FACE + LOCATION VERIFICATION MODAL (Clock In)
            ================================================= */}

            {showVerifyModal && (
                <div className="adm-verify-overlay" role="dialog" aria-modal="true">
                    <div className="adm-verify-card">
                        <div className="adm-verify-header">
                            <h3>Verify & Clock In</h3>
                            <button
                                type="button"
                                className="adm-verify-close"
                                onClick={closeVerifyModal}
                                aria-label="Close"
                            >
                                ×
                            </button>
                        </div>
                        <p className="adm-verify-hint">
                            Location verified ✓ — now capture a photo for face verification.
                        </p>
                        {verifyCoords && (
                            <div className="adm-verify-gps">
                                <LuMapPin />
                                <span>
                                    GPS: Lat {verifyCoords.latitude.toFixed(5)}, Lon {verifyCoords.longitude.toFixed(5)} (±{Math.round(verifyCoords.accuracy)}m)
                                </span>
                            </div>
                        )}
                        {verifyCoords && verifyCoords.accuracy > MAX_REPORTED_ACCURACY_METERS && (
                            <p className="adm-verify-gps-tip">
                                Accuracy margin is wide (±{Math.round(verifyCoords.accuracy)}m). For best results, enable High Accuracy GPS or move near an open window.
                            </p>
                        )}
                        <WebcamCapture
                            onCapture={setVerifyPhotoBlob}
                            onClear={() => setVerifyPhotoBlob(null)}
                            disabled={verifyLoading}
                        />
                        {verifyError && (
                            <>
                                <p className="adm-verify-error">{verifyError}</p>
                                <button
                                    type="button"
                                    className="adm-verify-btn adm-verify-retry"
                                    onClick={retryVerification}
                                    disabled={verifyLoading}
                                >
                                    Retry Verification
                                </button>
                            </>
                        )}
                        <div className="adm-verify-actions">
                            <button
                                type="button"
                                className="adm-verify-btn adm-verify-btn-secondary"
                                onClick={closeVerifyModal}
                                disabled={verifyLoading}
                            >
                                Cancel
                            </button>
                            <button
                                type="button"
                                className="adm-verify-btn adm-verify-btn-primary"
                                onClick={handleVerifyAndClockIn}
                                disabled={verifyLoading || !verifyPhotoBlob}
                            >
                                {verifyLoading ? "Verifying..." : "Verify & Clock In"}
                            </button>
                        </div>
                    </div>
                </div>
            )}

        </div>

    );

}

export default AdminDashboard;
