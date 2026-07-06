import { Routes, Route, Navigate } from "react-router-dom";
import MarketingLayout from "@/components/marketing/MarketingLayout";
import Home from "@/pages/marketing/Home";
import Product from "@/pages/marketing/Product";
import Integrations from "@/pages/marketing/Integrations";
import Security from "@/pages/marketing/Security";
import HowItWorks from "@/pages/marketing/HowItWorks";

/** Public marketing site: multi-page, client-side routed under a shared nav + footer. Rendered by App for a
 *  signed-out visitor on a marketing path; the CTAs full-navigate to /login and /signup to leave these routes. */
export default function MarketingSite() {
  return (
    <Routes>
      <Route element={<MarketingLayout />}>
        <Route path="/" element={<Home />} />
        <Route path="/product" element={<Product />} />
        <Route path="/integrations" element={<Integrations />} />
        <Route path="/security" element={<Security />} />
        <Route path="/how-it-works" element={<HowItWorks />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

/** The public paths the marketing site owns — App.tsx renders MarketingSite for a signed-out visit to any. */
export const MARKETING_PATHS = ["/", "/product", "/integrations", "/security", "/how-it-works"];
