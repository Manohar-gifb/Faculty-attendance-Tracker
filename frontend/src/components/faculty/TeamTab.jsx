import { useEffect, useState } from "react";

import {
    FaUsers,
    FaSearch,
    FaPhoneAlt,
    FaEnvelope,
    FaInfoCircle
} from "react-icons/fa";

import { teamAPI, imageSrc } from "../../services/api";
import { useAuth } from "../../context/AuthContext";


function TeamTab() {

    const { user } = useAuth();
    const [teamMembers, setTeamMembers] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [searchText, setSearchText] = useState("");


    // =====================================================
    // LOAD TEAM MEMBERS
    // =====================================================

    useEffect(() => {

        const loadTeam = async () => {

            try {
                setLoading(true);

                // Try to load from the team API
                const res = await teamAPI.getDepartmentFaculty();
                const members = Array.isArray(res.data) ? res.data : [];
                setTeamMembers(members);

            } catch (err) {
                console.error("Team load error:", err);

                // Fallback: show current user from auth context
                if (user) {
                    setTeamMembers([{
                        id: user.userId,
                        firstName: user.fullName?.split(" ")[0] || "",
                        lastName: user.fullName?.split(" ").slice(1).join(" ") || "",
                        fullName: user.fullName,
                        email: user.email,
                        phone: "N/A",
                        role: user.role,
                        departmentName: user.departmentName,
                        departmentCode: "N/A",
                        profilePictureUrl: user.profilePictureUrl,
                        isCurrentUser: true
                    }]);
                } else {
                    setError("Unable to load team members.");
                }
            } finally {
                setLoading(false);
            }

        };

        loadTeam();

    }, [user]);


    // =====================================================
    // FILTER MEMBERS
    // =====================================================

    const filteredMembers = teamMembers.filter((member) => {

        const fullName = member.fullName || `${member.firstName} ${member.lastName}`;

        return (
            fullName.toLowerCase().includes(searchText.toLowerCase()) ||
            (member.email && member.email.toLowerCase().includes(searchText.toLowerCase())) ||
            (member.employeeId && member.employeeId.toLowerCase().includes(searchText.toLowerCase()))
        );

    });


    // =====================================================
    // ROLE LABELS
    // =====================================================

    const roleLabels = {
        FACULTY: "Assistant Professor",
        HOD: "Head of Department",
        DEVELOPER: "System Developer",
        VC: "Vice Chancellor",
        PRINCIPAL: "Principal",
        TPO: "Training & Placement Officer"
    };


    // =====================================================
    // RENDER
    // =====================================================

    return (

        <div className="fd-team-tab">


            {/* =================================================
                HEADER
            ================================================= */}

            <div className="fd-team-header">
                <div className="fd-team-header-left">
                    <FaUsers className="fd-team-header-icon" />
                    <div>
                        <h1>{user?.fullName || "My Team"}</h1>
                        <p>Your department faculty members</p>
                    </div>
                </div>
            </div>


            {/* =================================================
                SEARCH
            ================================================= */}

            <div className="fd-team-search">
                <FaSearch className="fd-team-search-icon" />
                <input type="text" value={searchText}
                    onChange={(e) => setSearchText(e.target.value)}
                    placeholder="Search by name, email or ID..." />
            </div>


            {/* =================================================
                LOADING
            ================================================= */}

            {loading && (
                <div className="fd-inbox-loading">
                    <div className="fd-inbox-spinner"></div>
                    <span>Loading team members...</span>
                </div>
            )}


            {/* =================================================
                ERROR
            ================================================= */}

            {error && !loading && (
                <div className="fd-inbox-error">
                    <FaInfoCircle />
                    <span>{error}</span>
                </div>
            )}


            {/* =================================================
                TEAM COUNT
            ================================================= */}

            {!loading && !error && teamMembers.length > 0 && (
                <div className="fd-team-count">
                    {filteredMembers.length} member{filteredMembers.length !== 1 ? "s" : ""}
                    {searchText && ` found`}
                </div>
            )}


            {/* =================================================
                TEAM LIST
            ================================================= */}

            {!loading && !error && (

                <div className="fd-team-list">

                    {filteredMembers.length === 0 ? (
                        <div className="fd-inbox-empty">
                            <FaUsers className="fd-inbox-empty-icon" />
                            <h3>No Team Members</h3>
                            <p>{searchText ? "No members match your search." : "No team members found."}</p>
                        </div>
                    ) : (

                        filteredMembers.map((member) => {

                            const fullName = member.fullName || `${member.firstName} ${member.lastName}`;
                            const initials = (member.firstName?.charAt(0) || "") + (member.lastName?.charAt(0) || "");

                            return (

                                <div className="fd-team-card" key={member.id}>


                                    {/* AVATAR */}

                                    <div className="fd-team-avatar">
                                        {member.profilePictureUrl ? (
                                            <>
                                                <img
                                                    src={imageSrc(member.profilePictureUrl)}
                                                    alt={fullName}
                                                    onError={(e) => {
                                                        e.currentTarget.style.display = "none";
                                                        if (e.currentTarget.nextSibling) e.currentTarget.nextSibling.style.display = "flex";
                                                    }}
                                                />
                                                <span className="fd-team-initials" style={{ display: "none" }}>{initials || "U"}</span>
                                            </>
                                        ) : (
                                            <span className="fd-team-initials">{initials || "U"}</span>
                                        )}
                                    </div>


                                    {/* INFO */}

                                    <div className="fd-team-info">
                                        <div className="fd-team-name-row">
                                            <h3>{fullName}</h3>
                                            {member.isCurrentUser && (
                                                <span className="fd-team-you-badge">You</span>
                                            )}
                                        </div>
                                        <p className="fd-team-role">{roleLabels[member.role] || member.role}</p>
                                        <p className="fd-team-department">{member.departmentName}</p>

                                        <div className="fd-team-contact">
                                            <span><FaEnvelope /> {member.email}</span>
                                            {member.phone && member.phone !== "N/A" && (
                                                <span><FaPhoneAlt /> {member.phone}</span>
                                            )}
                                        </div>
                                    </div>

                                </div>

                            );

                        })

                    )}

                </div>

            )}

        </div>

    );

}

export default TeamTab;
