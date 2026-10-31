import { useEffect, useMemo, useState, useRef, useCallback } from "react";
import { useNavigate } from "react-router-dom";

import {
    FaClock,
    FaCalendarAlt,
    FaBullhorn,
    FaUmbrellaBeach,
    FaLocationArrow,
    FaInfoCircle,
    FaTimes,
    FaCheck,
    FaUserCircle,
    FaBuilding,
    FaShieldAlt,
    FaEnvelope,
    FaPhoneAlt,
    FaIdBadge,
    FaCamera,
    FaRedo,
    FaShieldAlt as FaShieldCheck
} from "react-icons/fa";

import dayjs from "dayjs";
import { attendanceAPI, teamAPI, imageSrc } from "../../services/api";

// =============================================================
// GPS ACCURACY HINT THRESHOLD
// Mirrors the backend default app.college.max-accuracy-meters.
// A wider reported margin only triggers a UX hint — the backend
// remains the sole authority on attendance eligibility.
// =============================================================
const MAX_REPORTED_ACCURACY_METERS = 150;

/** Map a getUserMedia camera error name into an actionable diagnostic message. */
const describeCameraError = (err) => {
    switch (err && err.name) {
        case "NotAllowedError":
        case "PermissionDeniedError":
            return "Camera access denied. Please click the lock/camera icon in your browser address bar and enable camera access.";
        case "NotFoundError":
        case "DevicesNotFoundError":
            return "No camera detected on this device. Please connect a webcam or switch to a device with a camera.";
        case "NotReadableError":
        case "TrackStartError":
            return "Camera is in use by another application or tab (e.g. Zoom, Teams). Please close other apps and try again.";
        case "OverconstrainedError":
            return "Requested camera resolution is not supported by your hardware.";
        default:
            return "Camera error: " + ((err && err.message) || "Unable to access camera.");
    }
};

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
            return "Unable to get your location. Please try again.";
    }
};


function HomeTab({ searchQuery = "" }) {

    const navigate = useNavigate();


    // =====================================================
    // COLLEGE LOCATION CONFIGURATION
    // =====================================================

    const COLLEGE_LATITUDE =
        Number(import.meta.env.VITE_COLLEGE_LATITUDE);

    const COLLEGE_LONGITUDE =
        Number(import.meta.env.VITE_COLLEGE_LONGITUDE);

    // Campus radius (metres) — a geofence value, NOT GPS accuracy.
    // Falls back to the documented backend default app.college.radius-meters
    // (3,800 m) when no env value is set. Display/advisory only; faculty
    // never call the admin location-config endpoint — the backend is the
    // sole authority on attendance eligibility.
    const ALLOWED_RADIUS_METERS =
        Number(import.meta.env.VITE_COLLEGE_RADIUS_METERS || 3800);


    // =====================================================
    // CONSTANTS
    // =====================================================

    const MONTH_NAMES = [
        "January", "February", "March", "April",
        "May", "June", "July", "August",
        "September", "October", "November", "December"
    ];

    const WEEK_DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];


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
    const [locationLoading] = useState(false);
    const [locationError, setLocationError] = useState("");
    const [locationVerified, setLocationVerified] = useState(false);
    const [currentDistance, setCurrentDistance] = useState(null);
    const [apiError, setApiError] = useState("");

    // ---- FACE VERIFICATION MODAL STATE ----
    const [showVerifyModal, setShowVerifyModal] = useState(false);
    const [verifyStep, setVerifyStep] = useState("idle"); // idle | camera | capturing | location | verifying | success | failed
    const [verifyError, setVerifyError] = useState("");
    const [verifyResult, setVerifyResult] = useState(null);
    const [verifyCoords, setVerifyCoords] = useState(null); // { latitude, longitude, accuracy } — display only; backend decides
    const verifyVideoRef = useRef(null);
    const verifyCanvasRef = useRef(null);
    const verifyStreamRef = useRef(null);
    const autoCaptureTimerRef = useRef(null);
    // Tracks whether a verification flow is live (duplicate-submission guard)
    // and a generation counter that invalidates stale async results whenever
    // the modal closes or the user retries.
    const verifyActiveRef = useRef(false);
    const verifyGenerationRef = useRef(0);

    const todayForHistory = new Date();
    const [historyMonth] = useState(todayForHistory.getMonth());
    const [historyYear] = useState(todayForHistory.getFullYear());
    const [historyData, setHistoryData] = useState([]);
    const [historyLoading, setHistoryLoading] = useState(true);
    const [historyError, setHistoryError] = useState("");    const [
        selectedHistoryDate,
        setSelectedHistoryDate
    ] = useState(null);

    // =====================================================
    // FACULTY LIST (DYNAMIC)
    // =====================================================

    const [facultyList, setFacultyList] = useState([]);
    const [facultyLoading, setFacultyLoading] = useState(true);

    // =====================================================
    // SELECTED FACULTY (PROFILE POPUP)
    // =====================================================

    const [selectedFaculty, setSelectedFaculty] = useState(null);

    // =====================================================
    // FILTERED FACULTY BY SEARCH
    // =====================================================

    const filteredFaculty = facultyList.filter((f) =>
        (f.fullName || "")
            .toLowerCase()
            .includes(searchQuery.toLowerCase())
    );

    // =====================================================
    // FORMAT ROLE NAME
    // =====================================================

    const formatRole = (role) => {
        if (!role) return "N/A";
        const roleMap = {
            FACULTY: "Assistant Professor",
            HOD: "Head of Department",
            VC: "Vice Chancellor",
            PRINCIPAL: "Principal",
            TPO: "Training & Placement Officer",
            DEVELOPER: "System Developer",
            LAB_ASSISTANT: "Lab Assistant",
            NON_TEACHING: "Non Teaching Staff"
        };
        return roleMap[role] || role.charAt(0) + role.slice(1).toLowerCase().replace(/_/g, " ");
    };


    // =====================================================
    // HELPERS
    // =====================================================

    const getLocalDateString = (date = new Date()) => {
        const y = date.getFullYear();
        const m = String(date.getMonth() + 1).padStart(2, "0");
        const d = String(date.getDate()).padStart(2, "0");
        return `${y}-${m}-${d}`;
    };

    const isLocationConfigured = () => {
        return (
            Number.isFinite(COLLEGE_LATITUDE) &&
            Number.isFinite(COLLEGE_LONGITUDE) &&
            Number.isFinite(ALLOWED_RADIUS_METERS) &&
            ALLOWED_RADIUS_METERS > 0
        );
    };

    // =====================================================
    // TIMERS
    // =====================================================

    useEffect(() => {
        const t = setInterval(() => setCurrentDate(new Date()), 1000);
        return () => clearInterval(t);
    }, []);

    useEffect(() => {
        if (!isClockedIn) return undefined;
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
    // LOAD DEPARTMENT FACULTY
    // =====================================================

    useEffect(() => {
        const load = async () => {
            try {
                setFacultyLoading(true);
                const res = await teamAPI.getDepartmentFaculty();
                setFacultyList(Array.isArray(res.data) ? res.data : []);
            } catch (err) {
                console.error("Faculty list error:", err);
                setFacultyList([]);
            } finally {
                setFacultyLoading(false);
            }
        };
        load();
    }, []);


    // =====================================================
    // LOAD HISTORY
    // =====================================================

    useEffect(() => {
        const load = async () => {
            try {
                setHistoryLoading(true);
                const start = dayjs().startOf("month").format("YYYY-MM-DD");
                const end = dayjs().endOf("month").format("YYYY-MM-DD");
                const res = await attendanceAPI.getHistory(start, end);
                setHistoryData(Array.isArray(res.data) ? res.data : []);
            } catch (err) {
                console.error("History load error:", err);
                setHistoryError(err.response?.data?.message || "Unable to load history.");
            } finally {
                setHistoryLoading(false);
            }
        };
        load();
    }, []);


    // =====================================================
    // COMPUTED VALUES
    // =====================================================

    const formattedDate = currentDate.toLocaleDateString("en-IN", {
        day: "2-digit", month: "long", year: "numeric"
    });

    const formatClockInTime = () => {
        if (!clockInTime) return "";
        return clockInTime.toLocaleTimeString("en-IN", {
            hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
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

    const getHistoryRecord = (date) => {
        const ds = getLocalDateString(date);
        return historyData.find((r) => r.attendanceDate === ds) || null;
    };

    const getAttendanceStatus = (record, date) => {
        if (!record) {
            const today = new Date(); today.setHours(0, 0, 0, 0);
            const check = new Date(date); check.setHours(0, 0, 0, 0);
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


    // =====================================================
    // STOP VERIFY CAMERA
    // =====================================================

    const stopVerifyCamera = useCallback(() => {
        if (verifyStreamRef.current) {
            verifyStreamRef.current.getTracks().forEach((t) => t.stop());
            verifyStreamRef.current = null;
        }
        if (verifyVideoRef.current) {
            verifyVideoRef.current.srcObject = null;
        }
        if (autoCaptureTimerRef.current) {
            clearTimeout(autoCaptureTimerRef.current);
            autoCaptureTimerRef.current = null;
        }
    }, []);

    // =====================================================
    // VERIFY FLOW LIFECYCLE
    // (open, retry, stale-result invalidation, cleanup)
    // =====================================================

    /** True only while the modal is open and the async work belongs to the current generation. */
    const isVerifyFlowAlive = useCallback((gen) => {
        return verifyActiveRef.current && verifyGenerationRef.current === gen;
    }, []);

    /** Acquire the camera stream, attach it, and start the auto-capture cycle. */
    const startVerification = useCallback(async () => {
        const gen = verifyGenerationRef.current;
        setVerifyStep("camera");

        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: "user" },
                audio: false,
            });
            verifyStreamRef.current = stream;
            // Wait for next render so videoRef is in DOM
            setTimeout(() => {
                if (verifyVideoRef.current) {
                    verifyVideoRef.current.srcObject = stream;
                    verifyVideoRef.current.play().catch(() => {});
                }
            }, 100);

            // Auto-capture after 3 seconds so user can position their face
            autoCaptureTimerRef.current = setTimeout(() => {
                handleAutoCapture(gen);
            }, 3000);
        } catch (err) {
            console.error("Camera error:", err);
            if (!isVerifyFlowAlive(gen)) return; // user closed/aborted before the camera answered
            setVerifyError(describeCameraError(err));
            setVerifyStep("failed");
        }
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    /**
     * Open the verification modal. Guarded so a second click cannot create a
     * duplicate camera stream or trigger parallel verification flows.
     */
    const openVerifyModal = useCallback(() => {
        if (verifyActiveRef.current) return;
        verifyActiveRef.current = true;

        setVerifyError("");
        setVerifyResult(null);
        setVerifyCoords(null);
        setShowVerifyModal(true);
        startVerification();
    }, [startVerification]);

    /**
     * In-modal retry: invalidates any in-flight async work, cleanly stops the
     * previous camera stream, and restarts the flow (camera → auto-capture →
     * GPS → verification) without closing or reloading anything.
     */
    const retryVerification = useCallback(() => {
        verifyGenerationRef.current += 1; // kill stale callbacks from the failed attempt
        setVerifyError("");
        setVerifyResult(null);
        setVerifyCoords(null);
        verifyActiveRef.current = true; // modal is already open — keep the flow alive
        stopVerifyCamera();
        startVerification();
    }, [startVerification, stopVerifyCamera]);

    // =====================================================
    // AUTO CAPTURE (called by timer)
    // Every async boundary is checked against the flow's current
    // generation, so results from an abandoned (closed/retried)
    // verification never re-show the modal or fire a punch-in.
    // =====================================================

    const handleAutoCapture = useCallback((gen) => {
        const video = verifyVideoRef.current;
        const canvas = verifyCanvasRef.current;
        if (!video || !canvas || !video.videoWidth) {
            // Retry once if video not ready
            autoCaptureTimerRef.current = setTimeout(() => handleAutoCapture(gen), 500);
            return;
        }

        setVerifyStep("capturing");

        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext("2d");
        // Mirror for front camera
        ctx.translate(canvas.width, 0);
        ctx.scale(-1, 1);
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        ctx.setTransform(1, 0, 0, 1, 0, 0);

        canvas.toBlob(async (blob) => {
            if (!isVerifyFlowAlive(gen)) return;
            if (!blob) {
                setVerifyError("Failed to capture photo.");
                setVerifyStep("failed");
                return;
            }

            // Convert to base64
            const reader = new FileReader();
            reader.onloadend = async () => {
                if (!isVerifyFlowAlive(gen)) return;
                const base64 = reader.result;

                // Now get location
                setVerifyStep("location");
                try {
                    const position = await new Promise((resolve, reject) => {
                        navigator.geolocation.getCurrentPosition(resolve, reject, {
                            enableHighAccuracy: true,
                            timeout: 10000,
                            maximumAge: 0,
                        });
                    });

                    if (!isVerifyFlowAlive(gen)) { stopVerifyCamera(); return; }

                    const { latitude, longitude, accuracy } = position.coords;
                    setVerifyCoords({ latitude, longitude, accuracy });

                    // Send to backend for verification
                    setVerifyStep("verifying");
                    try {
                        const res = await attendanceAPI.verifyAttendance({
                            capturedPhoto: base64,
                            latitude,
                            longitude,
                            accuracy,
                        });

                        if (!isVerifyFlowAlive(gen)) return; // abandoned: drop result, never punch in
                        const result = res.data;
                        setVerifyResult(result);

                        if (result.verified) {
                            setVerifyStep("success");
                            // Now punch in, presenting the single-use verification ticket
                            await performPunchIn(result.ticket, gen);
                        } else {
                            setVerifyStep("failed");
                            setVerifyError(result.message || "Verification failed.");
                        }
                    } catch (err) {
                        console.error("Verify API error:", err);
                        if (!isVerifyFlowAlive(gen)) return;
                        setVerifyStep("failed");
                        setVerifyError(err.response?.data?.message || err.response?.data?.error || "Verification service error. Please try again.");
                    }
                } catch (locErr) {
                    console.error("Location error:", locErr);
                    if (!isVerifyFlowAlive(gen)) return;
                    setVerifyStep("failed");
                    setVerifyError(describeGeolocationError(locErr));
                }
            };
            reader.readAsDataURL(blob);
        }, "image/jpeg", 0.92);
    }, [isVerifyFlowAlive, stopVerifyCamera]); // eslint-disable-line react-hooks/exhaustive-deps -- performPunchIn below is a stable useCallback

    // =====================================================
    // PERFORM PUNCH IN (after verification)
    // =====================================================

    const performPunchIn = useCallback(async (ticket, gen) => {
        try {
            // The ticket — issued by /attendance/verify on success — proves to the
            // backend that face + location verification just passed.
            const res = await attendanceAPI.punchIn(ticket);

            // Apply the attendance state even if the modal was closed while the
            // request was in flight (the ticket is already consumed server-side);
            // only the modal-specific error display is gated on the live flow.
            const data = res.data;
            const checkInTime = data.punchInTime ? new Date(data.punchInTime) : new Date();

            setClockInTime(checkInTime);
            setClockOutTime(null);
            setTimerNow(checkInTime);
            setIsClockedIn(true);

            setHistoryData((prev) => {
                const today = getLocalDateString();
                const exists = prev.some((r) => r.attendanceDate === today);
                if (exists) return prev.map((r) => r.attendanceDate === today ? { ...r, ...data } : r);
                return [...prev, data];
            });
        } catch (err) {
            console.error("Punch in error after verification:", err);
            const message = err.response?.data?.message || "Face verified but punch-in failed. Please try again.";
            if (gen === undefined || isVerifyFlowAlive(gen)) {
                setVerifyError(message);
                setVerifyStep("failed");
            } else {
                // Modal already closed: surface the failure on the attendance card instead
                setApiError(message);
            }
        }
    }, [isVerifyFlowAlive]);

    // =====================================================
    // CLOSE VERIFY MODAL
    // =====================================================

    const closeVerifyModal = useCallback(() => {
        verifyGenerationRef.current += 1; // invalidate stale async results
        verifyActiveRef.current = false;
        stopVerifyCamera();
        setShowVerifyModal(false);
        setVerifyStep("idle");
        setVerifyError("");
        setVerifyResult(null);
        setVerifyCoords(null);
    }, [stopVerifyCamera]);

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            verifyGenerationRef.current += 1;
            verifyActiveRef.current = false;
            stopVerifyCamera();
        };
    }, [stopVerifyCamera]);

    // =====================================================
    // CLOCK IN (opens verify modal)
    // =====================================================

    const handleClockIn = () => {
        if (verifyActiveRef.current) return; // a verification modal is already running
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
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            setLocationError("Camera not supported by this browser.");
            return;
        }

        openVerifyModal();
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

            setHistoryData((prev) => {
                const filtered = prev.filter((r) => r.attendanceDate !== data.attendanceDate);
                return [...filtered, data];
            });
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

        <div className="fd-home-tab">


            {/* =================================================
                FACULTY LIST
            ================================================= */}

            <section className="fd-faculty-section">

                <div className="fd-section-heading">
                    <h1>Faculty</h1>
                </div>

                <div className="fd-faculty-list">
                    {facultyLoading ? (
                        <span style={{ color: "#98a2b3", fontSize: "10px" }}>Loading...</span>
                    ) : filteredFaculty.length > 0 ? (
                        filteredFaculty.map((f) => (
                            <div className="fd-faculty-item" key={f.employeeId || f.id}
                                onClick={() => setSelectedFaculty(f)} style={{ cursor: "pointer" }}>
                                <div className="fd-faculty-avatar-wrapper">
                                    <div className="fd-faculty-avatar">
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
                                                    {f.fullName ? f.fullName.charAt(0).toUpperCase() : <FaUserCircle />}
                                                </span>
                                            </>
                                        ) : (
                                            <span>{f.fullName ? f.fullName.charAt(0).toUpperCase() : <FaUserCircle />}</span>
                                        )}
                                    </div>
                                    {f.isClockedIn && (
                                        <span className="fd-faculty-online-dot"></span>
                                    )}
                                </div>
                                <p>{f.fullName}</p>
                            </div>
                        ))
                    ) : (
                        <span style={{ color: "#98a2b3", fontSize: "10px" }}>
                            {searchQuery ? "No matching faculty" : "No faculty found"}
                        </span>
                    )}
                </div>

            </section>


            {/* =================================================
                FACULTY PROFILE POPUP
            ================================================= */}

            {selectedFaculty && (
                <div className="fd-faculty-popup-overlay"
                    onClick={() => setSelectedFaculty(null)}>
                    <div className="fd-faculty-popup"
                        onClick={(e) => e.stopPropagation()}>

                        <div className="fd-faculty-popup-header">
                            <div className="fd-faculty-popup-avatar">
                                {selectedFaculty.profilePictureUrl ? (
                                    <>
                                        <img
                                            src={imageSrc(selectedFaculty.profilePictureUrl)}
                                            alt={selectedFaculty.fullName}
                                            style={{ width: "100%", height: "100%", objectFit: "cover", borderRadius: "50%" }}
                                            onError={(e) => {
                                                e.currentTarget.style.display = "none";
                                                if (e.currentTarget.nextSibling) e.currentTarget.nextSibling.style.display = "inline";
                                            }}
                                        />
                                        <span style={{ display: "none" }}>
                                            {selectedFaculty.fullName ? selectedFaculty.fullName.charAt(0).toUpperCase() : <FaUserCircle />}
                                        </span>
                                    </>
                                ) : (
                                    <span>{selectedFaculty.fullName ? selectedFaculty.fullName.charAt(0).toUpperCase() : <FaUserCircle />}</span>
                                )}
                            </div>
                            <button type="button" className="fd-faculty-popup-close"
                                onClick={() => setSelectedFaculty(null)}>
                                <FaTimes />
                            </button>
                        </div>

                        <div className="fd-faculty-popup-body">
                            <h2>{selectedFaculty.fullName}</h2>

                            <div className="fd-faculty-popup-details">
                                <div className="fd-faculty-popup-row">
                                    <FaIdBadge className="fd-faculty-popup-icon" />
                                    <div>
                                        <span className="fd-faculty-popup-label">Employee ID</span>
                                        <span className="fd-faculty-popup-value">{selectedFaculty.employeeId || "N/A"}</span>
                                    </div>
                                </div>

                                <div className="fd-faculty-popup-row">
                                    <FaEnvelope className="fd-faculty-popup-icon" />
                                    <div>
                                        <span className="fd-faculty-popup-label">Email</span>
                                        <span className="fd-faculty-popup-value">{selectedFaculty.email}</span>
                                    </div>
                                </div>

                                {selectedFaculty.phone && (
                                    <div className="fd-faculty-popup-row">
                                        <FaPhoneAlt className="fd-faculty-popup-icon" />
                                        <div>
                                            <span className="fd-faculty-popup-label">Phone</span>
                                            <span className="fd-faculty-popup-value">{selectedFaculty.phone}</span>
                                        </div>
                                    </div>
                                )}

                                <div className="fd-faculty-popup-row">
                                    <FaBuilding className="fd-faculty-popup-icon" />
                                    <div>
                                        <span className="fd-faculty-popup-label">Department</span>
                                        <span className="fd-faculty-popup-value">{selectedFaculty.departmentName || "N/A"}</span>
                                    </div>
                                </div>

                                <div className="fd-faculty-popup-row">
                                    <FaShieldAlt className="fd-faculty-popup-icon" />
                                    <div>
                                        <span className="fd-faculty-popup-label">Role</span>
                                        <span className="fd-faculty-popup-value fd-faculty-popup-role">{formatRole(selectedFaculty.role)}</span>
                                    </div>
                                </div>
                            </div>
                        </div>

                    </div>
                </div>
            )}


            {/* =================================================
                ATTENDANCE CARD
            ================================================= */}

            <section className="fd-attendance-card">

                <div className="fd-attendance-information">
                    <div className="fd-working-information">
                        <span className="fd-information-label">TODAY&apos;S ATTENDANCE</span>
                        <h2>{isClockedIn ? "Working" : clockOutTime ? "Completed" : "Not Clocked In"}</h2>
                    </div>
                    <div className="fd-date-information">
                        <span className="fd-information-date">{formattedDate}</span>
                        {!isClockedIn && !clockOutTime && <span className="fd-shift-information">General Shift</span>}
                        {clockInTime && (
                            <div className="fd-attendance-result">
                                <span className="fd-clock-in-value">{formatClockInTime()}</span>
                                <span className="fd-result-separator">|</span>
                                <span className="fd-worked-value">{calculateWorkedTime()}</span>
                            </div>
                        )}
                    </div>
                </div>

                {locationVerified && (
                    <div className="fd-location-success">
                        <FaLocationArrow />
                        <span>Location Verified</span>
                        {currentDistance !== null && <span>{Math.round(currentDistance)}m</span>}
                    </div>
                )}

                {locationError && (
                    <div className="fd-location-error">
                        <FaInfoCircle /><span>{locationError}</span>
                    </div>
                )}

                {apiError && (
                    <div className="fd-location-error">
                        <FaInfoCircle /><span>{apiError}</span>
                    </div>
                )}

                {!isClockedIn ? (
                    <button type="button" className="fd-clock-button" onClick={handleClockIn}
                        disabled={locationLoading || clockActionLoading || attendanceLoading}>
                        {locationLoading || clockActionLoading ? <span className="fd-clock-loader" /> : <FaClock />}
                        <span>{locationLoading ? "Verifying Location..." : clockActionLoading ? "Processing..." : clockOutTime ? "Clock In Again" : "Clock In"}</span>
                    </button>
                ) : (
                    <button type="button" className="fd-clock-button fd-clock-out-button" onClick={handleClockOut}
                        disabled={clockActionLoading}>
                        {clockActionLoading ? <span className="fd-clock-loader" /> : <FaTimes />}
                        <span>{clockActionLoading ? "Processing..." : "Clock Out"}</span>
                    </button>
                )}

            </section>


            {/* =================================================
                ANNOUNCEMENT
            ================================================= */}

            <section className="fd-announcement-section">
                <div className="fd-announcement-heading-content">
                    <FaBullhorn /><h2>Announcement</h2>
                </div>
                <div className="fd-announcement-content">
                    <div className="fd-announcement-icon"><FaBullhorn /></div>
                    <div className="fd-announcement-details">
                        <h3>Important Faculty Notice</h3>
                        <p>Please ensure your daily attendance is marked correctly before leaving the campus.</p>
                    </div>
                    <span className="fd-announcement-today">Today</span>
                </div>
            </section>


            {/* =================================================
                LEAVE SECTION
            ================================================= */}

            <section className="fd-leave-section">
                <button type="button" className="fd-leave-card fd-apply-leave"
                    onClick={() => navigate("/apply-leave")}>
                    <div className="fd-leave-icon"><FaUmbrellaBeach /></div>
                    <div className="fd-leave-text">
                        <h3>Apply Leave</h3>
                        <p>Submit a new leave request</p>
                    </div>
                </button>
                <button type="button" className="fd-leave-card fd-balance-leave"
                    onClick={() => navigate("/leave-balance")}>
                    <div className="fd-leave-icon"><FaCalendarAlt /></div>
                    <div className="fd-leave-text">
                        <h3>Leave Balance</h3>
                        <p>View your available leaves</p>
                    </div>
                </button>
            </section>


            {/* =================================================
                MONTHLY ATTENDANCE
            ================================================= */}

            <section className="fd-attendance-history-section">
                <div className="fd-section-heading">
                    <h2>Monthly Attendance</h2>
                </div>
                <div className="fd-attendance-history-card">

                    <div className="fd-attendance-history-month-header">
                        <div className="fd-attendance-history-month">
                            <FaCalendarAlt />
                            <div>
                                <strong>{MONTH_NAMES[historyMonth]}</strong>
                                <span>{historyYear}</span>
                            </div>
                        </div>
                    </div>

                    {!historyLoading && (
                        <div className="fd-attendance-history-summary">
                            <div className="fd-attendance-history-summary-item">
                                <div>
                                    <span className="fd-history-summary-dot fd-history-dot-present"></span>
                                    <strong>{historySummary.present}</strong>
                                </div>
                                <span>Present</span>
                            </div>
                            <div className="fd-attendance-history-summary-item">
                                <div>
                                    <span className="fd-history-summary-dot fd-history-dot-absent"></span>
                                    <strong>{historySummary.absent}</strong>
                                </div>
                                <span>Absent</span>
                            </div>
                            <div className="fd-attendance-history-summary-item">
                                <div>
                                    <span className="fd-history-summary-dot fd-history-dot-leave"></span>
                                    <strong>{historySummary.leave}</strong>
                                </div>
                                <span>Leave</span>
                            </div>
                        </div>
                    )}

                    {historyError && (
                        <div className="fd-attendance-history-error">
                            <FaInfoCircle /><span>{historyError}</span>
                        </div>
                    )}

                    {historyLoading ? (
                        <div className="fd-attendance-history-loading">Loading attendance history...</div>
                    ) : (
                        <div className="fd-attendance-history-calendar">

                            <div className="fd-attendance-history-weekdays">
                                {WEEK_DAYS.map((d) => <span key={d}>{d}</span>)}
                            </div>

                            <div className="fd-attendance-history-grid">
                                {calendarDays.map((item) => {
                                    if (item.empty) return <div className="fd-attendance-history-empty" key={item.key} />;

                                    const date = item.date;
                                    const record = getHistoryRecord(date);
                                    const status = getAttendanceStatus(record, date);
                                    const ds = getLocalDateString(date);
                                    const isToday = getLocalDateString() === ds;
                                    const isSelected = selectedHistoryDate?.dateString === ds;

                                    let cls = "fd-attendance-history-unknown";
                                    if (status === "PRESENT") cls = "fd-attendance-history-present";
                                    else if (status === "ABSENT") cls = "fd-attendance-history-absent";
                                    else if (status === "LEAVE") cls = "fd-attendance-history-leave";
                                    else if (status === "FUTURE") cls = "fd-attendance-history-future";

                                    return (
                                        <button type="button" key={ds}
                                            className={`fd-attendance-history-day ${cls} ${isToday ? "fd-attendance-history-today" : ""} ${isSelected ? "fd-attendance-history-selected" : ""}`}
                                            onClick={() => setSelectedHistoryDate({ date, dateString: ds, record })}
                                            disabled={status === "FUTURE"}>
                                            <span>{date.getDate()}</span>
                                        </button>
                                    );
                                })}
                            </div>

                            <div className="fd-attendance-history-legend">
                                <span className="fd-history-legend-box fd-history-legend-unknown"></span>
                                <span>No Record</span>
                                <span className="fd-history-legend-box fd-history-legend-present"></span>
                                <span>Present</span>
                                <span className="fd-history-legend-box fd-history-legend-absent"></span>
                                <span>Absent</span>
                                <span className="fd-history-legend-box fd-history-legend-leave"></span>
                                <span>Leave</span>
                            </div>

                            {selectedHistoryDate && (
                                <div className="fd-attendance-history-detail">
                                    <div className="fd-attendance-history-detail-header">
                                        <div>
                                            <span>SELECTED DATE</span>
                                            <h3>{selectedHistoryDate.date.toLocaleDateString("en-IN", { day: "2-digit", month: "long", year: "numeric" })}</h3>
                                        </div>
                                        <button type="button" className="fd-attendance-history-close"
                                            onClick={() => setSelectedHistoryDate(null)}>
                                            <FaTimes />
                                        </button>
                                    </div>

                                    {selectedHistoryDate.record ? (
                                        <>
                                            <div className={`fd-attendance-history-status ${
                                                selectedHistoryDate.record.status === "ABSENT" ? "fd-history-status-absent" :
                                                selectedHistoryDate.record.status === "ON_LEAVE" ? "fd-history-status-leave" :
                                                "fd-history-status-present"
                                            }`}>
                                                <FaCheck /><span>{selectedHistoryDate.record.status || "PRESENT"}</span>
                                            </div>
                                            <div className="fd-attendance-history-details-grid">
                                                <div>
                                                    <span>PUNCH IN</span>
                                                    <strong>{selectedHistoryDate.record.punchInTime ? dayjs(selectedHistoryDate.record.punchInTime).format("HH:mm:ss") : "--"}</strong>
                                                </div>
                                                <div>
                                                    <span>PUNCH OUT</span>
                                                    <strong>{selectedHistoryDate.record.punchOutTime ? dayjs(selectedHistoryDate.record.punchOutTime).format("HH:mm:ss") : "--"}</strong>
                                                </div>
                                                <div>
                                                    <span>WORKED</span>
                                                    <strong>{selectedHistoryDate.record.workingHours ? `${selectedHistoryDate.record.workingHours}h` : "--"}</strong>
                                                </div>
                                            </div>
                                        </>
                                    ) : (
                                        <div className="fd-attendance-history-status fd-history-status-unknown">
                                            <FaInfoCircle /><span>No attendance record for this date.</span>
                                        </div>
                                    )}
                                </div>
                            )}

                        </div>
                    )}

                </div>
            </section>


            {/* =================================================
                FACE VERIFICATION MODAL
            ================================================= */}

            {showVerifyModal && (
            <div className="fv-modal-overlay" onClick={closeVerifyModal}>
                <div className="fv-modal" onClick={(e) => e.stopPropagation()}>

                    {/* MODAL HEADER */}
                    <div className="fv-modal-header">
                        <div className="fv-modal-header-left">
                            <FaShieldCheck className="fv-modal-header-icon" />
                            <div>
                                <h2>Verify Identity</h2>
                                <p>Face + Location verification for clock-in</p>
                            </div>
                        </div>
                        <button className="fv-modal-close" onClick={closeVerifyModal} type="button">
                            <FaTimes />
                        </button>
                    </div>


                    {/* CAMERA PREVIEW */}
                    {(verifyStep === "camera" || verifyStep === "capturing") && (
                        <div className="fv-camera-section">
                            <div className="fv-camera-wrapper">
                                <video
                                    ref={verifyVideoRef}
                                    autoPlay
                                    playsInline
                                    muted
                                    className="fv-camera-video"
                                    style={{ transform: "scaleX(-1)" }}
                                />
                                <div className="fv-camera-overlay">
                                    <div className="fv-camera-frame" />
                                </div>
                                {verifyStep === "camera" && (
                                    <div className="fv-camera-countdown">
                                        <span>Look at the camera...</span>
                                    </div>
                                )}
                                {verifyStep === "capturing" && (
                                    <div className="fv-camera-flash" />
                                )}
                            </div>
                            <canvas ref={verifyCanvasRef} style={{ display: "none" }} />
                            <p className="fv-camera-hint">
                                {verifyStep === "capturing"
                                    ? "📸 Photo captured!"
                                    : "Position your face in the circle. Auto-capturing in 3 seconds..."}
                            </p>
                        </div>
                    )}


                    {/* LOCATION CHECK */}
                    {verifyStep === "location" && (
                        <div className="fv-status-section">
                            <div className="fv-status-icon fv-status-location">
                                <FaLocationArrow />
                            </div>
                            <h3>Checking Location...</h3>
                            <p>Verifying you are within campus</p>
                            <div className="fv-status-spinner" />
                        </div>
                    )}


                    {/* VERIFYING FACE */}
                    {verifyStep === "verifying" && (
                        <div className="fv-status-section">
                            <div className="fv-status-icon fv-status-verifying">
                                <FaCamera />
                            </div>
                            <h3>Verifying Face...</h3>
                            <p>Comparing with your profile photo</p>
                            <div className="fv-status-spinner" />
                            {verifyCoords && (
                                <div className="fv-gps-badge">
                                    <FaLocationArrow />
                                    <span>
                                        GPS: Lat {verifyCoords.latitude.toFixed(5)}, Lon {verifyCoords.longitude.toFixed(5)} (±{Math.round(verifyCoords.accuracy)}m)
                                    </span>
                                </div>
                            )}
                            {verifyCoords && verifyCoords.accuracy > MAX_REPORTED_ACCURACY_METERS && (
                                <p className="fv-gps-tip">
                                    Accuracy margin is wide (±{Math.round(verifyCoords.accuracy)}m). For best results, enable High Accuracy GPS or move near an open window.
                                </p>
                            )}
                        </div>
                    )}


                    {/* SUCCESS */}
                    {verifyStep === "success" && (
                        <div className="fv-status-section fv-success">
                            <div className="fv-status-icon fv-status-success">
                                <FaCheck />
                            </div>
                            <h3>Verification Passed!</h3>
                            <p>Face matched &bull; Location verified</p>
                            {verifyResult && (
                                <div className="fv-result-details">
                                    <span>Face confidence: {verifyResult.faceConfidence}%</span>
                                    <span>Distance: {Math.round(verifyResult.distanceFromCollege)}m</span>
                                    {verifyResult.reportedAccuracy != null && (
                                        <span>Accuracy: ±{Math.round(verifyResult.reportedAccuracy)}m</span>
                                    )}
                                </div>
                            )}
                        </div>
                    )}


                    {/* FAILED */}
                    {verifyStep === "failed" && (
                        <div className="fv-status-section fv-failed">
                            <div className="fv-status-icon fv-status-failed">
                                <FaTimes />
                            </div>
                            <h3>Verification Failed</h3>
                            <p className="fv-error-msg">{verifyError}</p>
                            {verifyResult && (
                                <div className="fv-result-details fv-result-fail">
                                    {verifyResult.faceMessage && <span>{verifyResult.faceMessage}</span>}
                                    {verifyResult.locationMessage && <span>{verifyResult.locationMessage}</span>}
                                    {verifyResult.reportedAccuracy != null && (
                                        <span>GPS Accuracy: ±{Math.round(verifyResult.reportedAccuracy)}m</span>
                                    )}
                                </div>
                            )}
                            <button className="fv-retry-btn" onClick={retryVerification}>
                                <FaRedo /> Try Again
                            </button>
                        </div>
                    )}


                </div>
            </div>
            )}

        </div>

    );

}

export default HomeTab;
