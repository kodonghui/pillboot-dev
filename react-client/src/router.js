import { createBrowserRouter } from 'react-router'
import { GuestLayout, MemberLayout } from './components/Layout.jsx'
import SessionGate from './components/SessionGate.jsx'
import CalendarPage from './pages/CalendarPage.jsx'
import ChatPage from './pages/ChatPage.jsx'
import DosesPage from './pages/DosesPage.jsx'
import DrugDetailPage from './pages/DrugDetailPage.jsx'
import DrugRegisterPage from './pages/DrugRegisterPage.jsx'
import DrugSearchPage from './pages/DrugSearchPage.jsx'
import DrugsPage from './pages/DrugsPage.jsx'
import HomePage from './pages/HomePage.jsx'
import LoginPage from './pages/LoginPage.jsx'
import NotFoundPage from './pages/NotFoundPage.jsx'
import NotificationsPage from './pages/NotificationsPage.jsx'
import PillPhotoPage from './pages/PillPhotoPage.jsx'
import PrescriptionPhotoPage from './pages/PrescriptionPhotoPage.jsx'
import ProfileEditPage from './pages/ProfileEditPage.jsx'
import ProfileNewPage from './pages/ProfileNewPage.jsx'
import ProfilesPage from './pages/ProfilesPage.jsx'
import ScheduleEditPage from './pages/ScheduleEditPage.jsx'
import ScheduleNewPage from './pages/ScheduleNewPage.jsx'
import SessionExpiredPage from './pages/SessionExpiredPage.jsx'
import SignupInfoPage from './pages/SignupInfoPage.jsx'
import SignupTermsPage from './pages/SignupTermsPage.jsx'
import WelcomePage from './pages/WelcomePage.jsx'
import { paths } from './paths.js'

// 일정 추가 · 수정은 주소로 헤더 메뉴가 정해지지 않는다. 약 상세에서 왔으면 약 목록, 아니면 복용 달력이 지금 메뉴다(목업 SCH-03 · 06).
function scheduleMenu({ search }) {
  return new URLSearchParams(search).get('from') === 'drug' ? paths.drugs : paths.calendar
}

export const router = createBrowserRouter([
  {
    Component: SessionGate,
    children: [
      {
        Component: GuestLayout,
        children: [
          { path: paths.welcome, Component: WelcomePage, handle: { footer: true } },
          { path: paths.signup, Component: SignupTermsPage },
          { path: paths.signupInfo, Component: SignupInfoPage },
          { path: paths.login, Component: LoginPage },
          { path: paths.sessionExpired, Component: SessionExpiredPage },
        ],
      },
      // handle.profileSwitcher: 헤더 "관리할 사람"(PRF-04)을 보일 화면. 'on' 바꿀 수 있음 · 'locked' 보이지만 바꿀 수 없음.
      // 없으면 숨긴다(복용 달력 · 약 상세 · 일정 · 알림 · 프로필 관리 · 오류 화면).
      // handle.menu(location): 헤더 주 메뉴에서 지금 위치로 표시할 주소. 없으면 화면 주소로 정한다.
      {
        Component: MemberLayout,
        children: [
          { path: paths.home, Component: HomePage, handle: { profileSwitcher: 'on' } },
          { path: paths.drugs, Component: DrugsPage, handle: { profileSwitcher: 'on' } },
          { path: paths.drugNew, Component: DrugRegisterPage, handle: { profileSwitcher: 'on' } },
          { path: paths.drugSearch, Component: DrugSearchPage, handle: { profileSwitcher: 'locked' } },
          { path: paths.prescriptionPhoto, Component: PrescriptionPhotoPage, handle: { profileSwitcher: 'locked' } },
          { path: paths.pillPhoto, Component: PillPhotoPage, handle: { profileSwitcher: 'locked' } },
          { path: paths.drugDetail, Component: DrugDetailPage },
          { path: paths.calendar, Component: CalendarPage },
          { path: paths.scheduleNew, Component: ScheduleNewPage, handle: { menu: scheduleMenu } },
          { path: paths.scheduleEdit, Component: ScheduleEditPage, handle: { menu: scheduleMenu } },
          { path: paths.chat, Component: ChatPage, handle: { profileSwitcher: 'on' } },
          { path: paths.chatRoom, Component: ChatPage, handle: { profileSwitcher: 'on' } },
          { path: paths.profiles, Component: ProfilesPage },
          { path: paths.profileNew, Component: ProfileNewPage },
          { path: paths.profileEdit, Component: ProfileEditPage },
          { path: paths.notifications, Component: NotificationsPage },
          { path: paths.doses, Component: DosesPage },
          { path: '*', Component: NotFoundPage },
        ],
      },
    ],
  },
])
