import { BrowserRouter } from "react-router-dom";
import Router from "./Router";

const basename = (window && window['CTX_PATH']) || "/";

function App() {
  return (
    <BrowserRouter basename={basename}>
      <Router />
    </BrowserRouter>
  )
}

export default App;
