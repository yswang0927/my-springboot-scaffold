import { BrowserRouter } from "react-router-dom";
import Router from "./Router";

const basename = (window && window['APP_BASE_URL']) || "/";

function App() {
  return (
    <BrowserRouter basename={basename}>
      <Router />
    </BrowserRouter>
  )
}

export default App;
