import ProfileTab from "../components/faculty/ProfileTab";


export default function ProfilePage() {

    // ProfileTab handles its own back navigation via logout
    // We just need to render it inside the dashboard wrapper

    return (

        <div className="fd-dashboard fd-profile-page">

            {/* MAIN CONTENT - ProfileTab has its own header with logout */}
            <main className="fd-main" style={{ paddingTop: "0" }}>
                <ProfileTab />
            </main>

        </div>

    );

}
