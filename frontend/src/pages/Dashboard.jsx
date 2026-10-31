import "./Dashboard.css";

import { useState } from "react";
import { useNavigate } from "react-router-dom";

import {
    FaHome,
    FaInbox,
    FaUser,
    FaUsers,
    FaSignOutAlt,
    FaUserCircle,
    FaSearch
} from "react-icons/fa";

import { useAuth } from "../context/AuthContext";
import { imageSrc } from "../services/api";

import HomeTab from "../components/faculty/HomeTab";
import InboxTab from "../components/faculty/InboxTab";
import ProfileTab from "../components/faculty/ProfileTab";
import TeamTab from "../components/faculty/TeamTab";


function Dashboard() {

    const navigate = useNavigate();
    const { user, logout } = useAuth();

    const [
        activeTab,
        setActiveTab
    ] = useState("HOME");

    const [
        searchQuery,
        setSearchQuery
    ] = useState("");


    // =====================================================
    // LOGOUT
    // =====================================================

    const handleLogout = () => {

        logout();
        navigate("/login", { replace: true });

    };


    // =====================================================
    // RENDER TAB CONTENT
    // =====================================================

    const renderTabContent = () => {

        switch (activeTab) {

            case "HOME":
                return <HomeTab searchQuery={searchQuery} />;

            case "INBOX":
                return <InboxTab />;

            case "ME":
                return <ProfileTab />;

            case "MY TEAM":
                return <TeamTab />;

            default:
                return <HomeTab />;

        }

    };


    // =====================================================
    // NAVIGATION ITEMS
    // =====================================================

    const navItems = [
        { name: "HOME", icon: FaHome },
        { name: "INBOX", icon: FaInbox },
        { name: "ME", icon: FaUser },
        { name: "MY TEAM", icon: FaUsers }
    ];


    // =====================================================
    // RENDER
    // =====================================================

    return (

        <div className="fd-dashboard">


            {/* =================================================
                HEADER - ONLY ON HOME TAB
            ================================================= */}

            {activeTab === "HOME" && (

                <header className="fd-header">

                    <button
                        type="button"
                        className="fd-profile-button"
                        onClick={() => setActiveTab("ME")}
                        aria-label="Open profile"
                        style={{ overflow: "hidden", display: "flex", alignItems: "center", justifyContent: "center" }}
                    >
                        {user?.profilePictureUrl ? (
                            <>
                                <img
                                    src={imageSrc(user.profilePictureUrl)}
                                    alt={user.fullName || "Profile"}
                                    style={{ width: "100%", height: "100%", objectFit: "cover", borderRadius: "50%" }}
                                    onError={(e) => {
                                        e.currentTarget.style.display = 'none';
                                        if (e.currentTarget.nextSibling) e.currentTarget.nextSibling.style.display = 'inline';
                                    }}
                                />
                                <span style={{ display: 'none' }}><FaUserCircle /></span>
                            </>
                        ) : (
                            <FaUserCircle />
                        )}
                    </button>


                    <div className="fd-search">
                        <FaSearch className="fd-search-icon" />
                        <input
                            type="text"
                            placeholder="Search faculty..."
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                        />
                    </div>


                    <button
                        type="button"
                        className="fd-logout"
                        onClick={handleLogout}
                    >
                        <FaSignOutAlt />
                        <span>Logout</span>
                    </button>

                </header>

            )}


            {/* =================================================
                MAIN CONTENT
            ================================================= */}

            <main className="fd-main">
                {renderTabContent()}
            </main>


            {/* =================================================
                BOTTOM NAVIGATION
            ================================================= */}

            <nav className="fd-navigation" aria-label="Faculty navigation">

                {navItems.map((item) => (

                    <button
                        type="button"
                        key={item.name}
                        className={
                            `fd-navigation-item ${
                                activeTab === item.name
                                    ? "fd-navigation-active"
                                    : ""
                            }`
                        }
                        onClick={() => setActiveTab(item.name)}
                    >
                        <item.icon />
                        <span>{item.name}</span>
                    </button>

                ))}

            </nav>

        </div>

    );

}

export default Dashboard;
