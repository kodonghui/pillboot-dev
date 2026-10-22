import { createBrowserRouter } from 'react-router'
import { GuestLayout, MemberLayout } from './components/Layout.jsx'
import SessionGate from './components/SessionGate.jsx'
import LoginPage from './pages/LoginPage.jsx'
import NotFoundPage from './pages/NotFoundPage.jsx'
import SessionExpiredPage from './pages/SessionExpiredPage.jsx'
import SignupInfoPage from './pages/SignupInfoPage.jsx'
import SignupTermsPage from './pages/SignupTermsPage.jsx'
import WelcomePage from './pages/WelcomePage.jsx'
import { paths } from './paths.js'

// 아직 만들지 않은 화면. 레이아웃만 보이고 본문은 비어 있다.
function NotBuilt() {
  return null
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
      {
        Component: MemberLayout,
        children: [
          { path: paths.home, Component: NotBuilt },
          { path: paths.drugs, Component: NotBuilt },
          { path: paths.calendar, Component: NotBuilt },
          { path: paths.chat, Component: NotBuilt },
          { path: paths.profiles, Component: NotBuilt },
          { path: paths.profileNew, Component: NotBuilt },
          { path: paths.notifications, Component: NotBuilt },
          { path: '*', Component: NotFoundPage },
        ],
      },
    ],
  },
])
