import { createRoot } from 'react-dom/client';
import { FieldApp } from './FieldApp';
import './ui/styles.css';

const el = document.getElementById('root');
if (el) createRoot(el).render(<FieldApp />);
