import { createRoot } from 'react-dom/client';
import { DialogApp } from './DialogApp';
import './ui/styles.css';

const el = document.getElementById('root');
if (el) createRoot(el).render(<DialogApp />);
